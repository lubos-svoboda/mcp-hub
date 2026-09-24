package cz.lubos.mcphub.entra

import org.springframework.stereotype.Component
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Sign-ins started here and not yet brought back by the browser. The state ties the answer to its
 * start, so an answer this server did not ask for is refused, and the PKCE verifier never leaves the
 * server, so a code caught on the way is useless to anybody else.
 */
@Component
class EntraSignIn(
    private val entraAccountRegistry: EntraAccountRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val random = SecureRandom()
    private val pendingByState = ConcurrentHashMap<String, PendingSignIn>()

    fun start(accountName: String, redirectUri: String): URI {
        val account = entraAccountRegistry.requireAccount(accountName)
        discardExpired()

        val state = randomValue()
        val codeVerifier = randomValue()
        pendingByState[state] = PendingSignIn(accountName, redirectUri, codeVerifier, clock.instant())
        return account.authorizationUrl(redirectUri, state, codeChallenge(codeVerifier))
    }

    /** Completes the sign-in the state belongs to and names its account. A state is good for one answer only. */
    fun complete(state: String, code: String): String {
        val pending = takePending(state)
        entraAccountRegistry.requireAccount(pending.accountName)
            .completeSignIn(code, pending.redirectUri, pending.codeVerifier)
        return pending.accountName
    }

    /** Entra answered with an error instead of a code; the account keeps it as its last error. */
    fun fail(state: String, reason: String): String {
        val pending = takePending(state)
        entraAccountRegistry.requireAccount(pending.accountName).recordSignInFailure(reason)
        return pending.accountName
    }

    private fun takePending(state: String): PendingSignIn {
        val pending = pendingByState.remove(state)
        require(pending != null && !isExpired(pending)) {
            "This sign-in was not started here, was already completed or took longer than " +
                "${PENDING_LIFETIME.toMinutes()} minutes. Start it again on the status page."
        }
        return pending
    }

    private fun discardExpired() = pendingByState.values.removeIf(::isExpired)

    private fun isExpired(pending: PendingSignIn) = pending.startedAt.plus(PENDING_LIFETIME).isBefore(clock.instant())

    private fun randomValue(): String =
        ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private class PendingSignIn(
        val accountName: String,
        val redirectUri: String,
        val codeVerifier: String,
        val startedAt: Instant,
    )

    companion object {
        val PENDING_LIFETIME: Duration = Duration.ofMinutes(10)

        /** RFC 7636, method S256. */
        fun codeChallenge(codeVerifier: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII)))
    }
}
