package cz.lubos.mcphub.entra

import cz.lubos.mcphub.config.EntraAccountProperties
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant

enum class EntraAccountState {
    SIGNED_OUT,
    SIGNED_IN,

    /** Entra refused the refresh token, for example after its sign-in frequency ran out. */
    SIGN_IN_REQUIRED,
}

data class EntraAccountStatus(
    val state: EntraAccountState,
    val user: String? = null,
    val signedInSince: Instant? = null,
    val accessTokenValidUntil: Instant? = null,
    val lastRefresh: Instant? = null,
    val lastError: String? = null,
)

/**
 * One signed-in identity and its current access token. Every new database connection asks for the
 * token here; an established connection keeps working after the token expires, because the server
 * checks the token only when a connection signs in.
 */
class EntraAccount(
    val name: String,
    private val properties: EntraAccountProperties,
    private val tokenClient: EntraTokenClient,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val lock = Any()

    @Volatile
    private var token: EntraToken? = null

    @Volatile
    private var status = EntraAccountStatus(EntraAccountState.SIGNED_OUT)

    fun status(): EntraAccountStatus = status

    fun authorizationUrl(redirectUri: String, state: String, codeChallenge: String): URI =
        tokenClient.authorizationUrl(redirectUri, state, codeChallenge, properties.expectedUser)

    fun completeSignIn(code: String, redirectUri: String, codeVerifier: String) {
        synchronized(lock) {
            val signedIn = try {
                tokenClient.redeemCode(code, redirectUri, codeVerifier)
            } catch (failure: Exception) {
                status = status.copy(lastError = "Sign-in failed: ${failure.message}")
                throw failure
            }
            refuseUnexpected(signedIn)

            token = signedIn
            status = EntraAccountStatus(
                state = EntraAccountState.SIGNED_IN,
                user = signedIn.user,
                signedInSince = clock.instant(),
                accessTokenValidUntil = signedIn.expiresAt,
            )
            logger.info("Entra account {} signed in as {} in tenant {}", name, signedIn.user, signedIn.tenantId)
        }
    }

    fun recordSignInFailure(reason: String) {
        status = status.copy(lastError = "Sign-in failed: $reason")
        logger.warn("Entra account {} could not sign in: {}", name, reason)
    }

    /** Fails at once when nobody is signed in, so a caller does not wait for a pool that cannot connect. */
    fun requireSignedIn() {
        if (token == null) {
            throw EntraSignInRequiredException(signInRequiredMessage())
        }
    }

    /** The token a new connection signs in with, renewed shortly before it runs out. */
    fun accessToken(): EntraToken {
        token?.takeIf(::isFresh)?.let { return it }
        synchronized(lock) {
            val current = token ?: throw EntraSignInRequiredException(signInRequiredMessage())
            if (isFresh(current)) {
                return current
            }
            return renew(current)
        }
    }

    fun signOut() {
        synchronized(lock) {
            tokenClient.forget()
            token = null
            status = EntraAccountStatus(EntraAccountState.SIGNED_OUT)
            logger.info("Entra account {} signed out", name)
        }
    }

    private fun renew(current: EntraToken): EntraToken =
        try {
            tokenClient.refresh().also { renewed ->
                token = renewed
                status = status.copy(
                    accessTokenValidUntil = renewed.expiresAt,
                    lastRefresh = clock.instant(),
                    lastError = null,
                )
                logger.debug("Entra account {} renewed its access token until {}", name, renewed.expiresAt)
            }
        } catch (failure: EntraSignInRequiredException) {
            token = null
            status = status.copy(
                state = EntraAccountState.SIGN_IN_REQUIRED,
                accessTokenValidUntil = null,
                lastError = failure.message,
            )
            logger.warn("Entra account {} must sign in again: {}", name, failure.message)
            throw EntraSignInRequiredException(signInRequiredMessage(), failure)
        } catch (failure: Exception) {
            // Entra may be unreachable for a moment; the token still in hand serves until it expires.
            status = status.copy(lastError = "Refresh failed: ${failure.message}")
            logger.warn("Entra account {} could not renew its access token: {}", name, failure.message)
            if (current.expiresAt.isAfter(clock.instant())) {
                current
            } else {
                throw IllegalStateException("Entra account $name could not renew its expired access token: ${failure.message}", failure)
            }
        }

    private fun refuseUnexpected(signedIn: EntraToken) {
        val reason = when {
            !signedIn.tenantId.equals(properties.tenantId, ignoreCase = true) ->
                "the sign-in belongs to tenant ${signedIn.tenantId}, not to ${properties.tenantId}"
            properties.expectedUser != null && !signedIn.user.equals(properties.expectedUser, ignoreCase = true) ->
                "${signedIn.user} signed in, but the account expects ${properties.expectedUser}"
            else -> return
        }
        tokenClient.forget()
        token = null
        status = EntraAccountStatus(EntraAccountState.SIGNED_OUT, lastError = "Sign-in refused: $reason")
        logger.warn("Entra account {} refused a sign-in: {}", name, reason)
        throw IllegalArgumentException("Entra account $name refused the sign-in: $reason.")
    }

    private fun isFresh(candidate: EntraToken) = candidate.expiresAt.isAfter(clock.instant().plus(RENEWAL_MARGIN))

    private fun signInRequiredMessage(): String {
        val reason = status.lastError?.let { " Last error: $it" } ?: ""
        return when (status.state) {
            EntraAccountState.SIGN_IN_REQUIRED -> "Entra account $name must sign in again on the status page.$reason"
            else -> "Entra account $name is not signed in; sign in on the status page."
        }
    }

    private companion object {
        /** A connection may take seconds to sign in; a token about to expire could run out on the way. */
        val RENEWAL_MARGIN: Duration = Duration.ofMinutes(5)
    }
}
