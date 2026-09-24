package cz.lubos.mcphub.entra

import cz.lubos.mcphub.config.EntraAccountProperties
import java.net.URI
import java.time.Instant

/** An access token together with what the hub needs to know about it; the token itself is never rendered. */
class EntraToken(
    val accessToken: String,
    val expiresAt: Instant,
    /** The signed-in user as Entra names them, usually the user principal name. */
    val user: String,
    val tenantId: String,
) {
    override fun toString() = "EntraToken(user=$user, tenantId=$tenantId, expiresAt=$expiresAt)"
}

/** Entra no longer accepts the sign-in, so only signing in again helps. */
class EntraSignInRequiredException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * The conversation with Entra ID for one account. Kept behind an interface because no test can sign
 * in to a real directory; everything above it is exercised with a stand-in.
 */
interface EntraTokenClient {

    fun authorizationUrl(redirectUri: String, state: String, codeChallenge: String, loginHint: String?): URI

    fun redeemCode(code: String, redirectUri: String, codeVerifier: String): EntraToken

    /**
     * A token for the account signed in last, renewed with its refresh token once the current one runs
     * out. Throws [EntraSignInRequiredException] when Entra refuses the refresh token.
     */
    fun refresh(): EntraToken

    fun forget()
}

fun interface EntraTokenClientFactory {
    fun create(accountProperties: EntraAccountProperties): EntraTokenClient
}
