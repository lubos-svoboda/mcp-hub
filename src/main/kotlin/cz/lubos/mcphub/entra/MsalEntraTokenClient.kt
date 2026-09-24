package cz.lubos.mcphub.entra

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.aad.msal4j.AuthorizationCodeParameters
import com.microsoft.aad.msal4j.AuthorizationRequestUrlParameters
import com.microsoft.aad.msal4j.IAccount
import com.microsoft.aad.msal4j.IAuthenticationResult
import com.microsoft.aad.msal4j.MsalInteractionRequiredException
import com.microsoft.aad.msal4j.Prompt
import com.microsoft.aad.msal4j.PublicClientApplication
import com.microsoft.aad.msal4j.ResponseMode
import com.microsoft.aad.msal4j.SilentParameters
import cz.lubos.mcphub.config.EntraAccountProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * MSAL keeps the tokens in memory only and takes care of the refresh token, including replacing it
 * with the new one Entra hands out on every refresh.
 */
class MsalEntraTokenClient(accountProperties: EntraAccountProperties) : EntraTokenClient {

    private val application = PublicClientApplication.builder(accountProperties.clientId)
        .authority("https://login.microsoftonline.com/${accountProperties.tenantId}/")
        .build()
    private val objectMapper = ObjectMapper()

    @Volatile
    private var account: IAccount? = null

    override fun authorizationUrl(redirectUri: String, state: String, codeChallenge: String, loginHint: String?): URI =
        application.getAuthorizationRequestUrl(
            AuthorizationRequestUrlParameters.builder(redirectUri, SCOPES)
                .state(state)
                .codeChallenge(codeChallenge)
                .codeChallengeMethod("S256")
                // MSAL answers with form_post whatever is asked for, so the code never appears in an address.
                .responseMode(ResponseMode.FORM_POST)
                .prompt(Prompt.SELECT_ACCOUNT)
                .apply { loginHint?.let(::loginHint) }
                .build(),
        ).toURI()

    override fun redeemCode(code: String, redirectUri: String, codeVerifier: String): EntraToken {
        val result = await(
            application.acquireToken(
                AuthorizationCodeParameters.builder(code, URI(redirectUri))
                    .scopes(SCOPES)
                    .codeVerifier(codeVerifier)
                    .build(),
            ),
        )
        account = result.account()
        return toToken(result)
    }

    override fun refresh(): EntraToken {
        val signedInAccount = account ?: throw EntraSignInRequiredException("Nobody is signed in.")
        return toToken(await(application.acquireTokenSilently(SilentParameters.builder(SCOPES, signedInAccount).build())))
    }

    override fun forget() {
        account?.let { signedInAccount -> await(application.removeAccount(signedInAccount)) }
        account = null
    }

    private fun toToken(result: IAuthenticationResult): EntraToken {
        val claims = objectMapper.readTree(Base64.getUrlDecoder().decode(result.accessToken().split('.')[1]))
        return EntraToken(
            accessToken = result.accessToken(),
            expiresAt = result.expiresOnDate().toInstant(),
            user = listOf("upn", "unique_name", "preferred_username")
                .firstNotNullOfOrNull { claims.path(it).textValue() }
                ?: result.account().username(),
            tenantId = claims.path("tid").asText(),
        )
    }

    /** MSAL answers with futures; their failures are unwrapped so that the reason reaches the caller. */
    private fun <T> await(future: CompletableFuture<T>): T =
        try {
            future.get()
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause ?: failure) {
                is MsalInteractionRequiredException -> throw EntraSignInRequiredException(
                    "Entra asks for a new sign-in (${cause.errorCode()}): ${cause.message?.lineSequence()?.firstOrNull()}",
                    cause,
                )
                is RuntimeException -> throw cause
                else -> throw IllegalStateException(cause.message ?: cause.javaClass.simpleName, cause)
            }
        }

    private companion object {
        /** Azure Database for PostgreSQL and MySQL accept tokens issued for this resource. */
        val SCOPES = setOf("https://ossrdbms-aad.database.windows.net/.default")
    }
}

@Component
class MsalEntraTokenClientFactory : EntraTokenClientFactory {
    override fun create(accountProperties: EntraAccountProperties): EntraTokenClient = MsalEntraTokenClient(accountProperties)
}
