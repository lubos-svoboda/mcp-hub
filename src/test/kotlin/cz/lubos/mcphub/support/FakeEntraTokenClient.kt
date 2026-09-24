package cz.lubos.mcphub.support

import cz.lubos.mcphub.entra.EntraToken
import cz.lubos.mcphub.entra.EntraTokenClient
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Stands in for Entra ID, which no test can sign in to. It issues whatever token it is told to, so
 * a test database can accept that token as the password of a role.
 */
class FakeEntraTokenClient(private val clock: Clock) : EntraTokenClient {

    var user = USER
    var tenantId = TENANT_ID
    var nextAccessToken = "first-access-token"
    var validFor: Duration = Duration.ofMinutes(60)
    var refreshFailure: Exception? = null

    var refreshCount = 0
        private set
    var forgetCount = 0
        private set
    var redeemedCode: String? = null
        private set
    var redeemedCodeVerifier: String? = null
        private set

    override fun authorizationUrl(redirectUri: String, state: String, codeChallenge: String, loginHint: String?): URI =
        URI("https://login.example.com/authorize?state=$state&code_challenge=$codeChallenge")

    override fun redeemCode(code: String, redirectUri: String, codeVerifier: String): EntraToken {
        redeemedCode = code
        redeemedCodeVerifier = codeVerifier
        return issue()
    }

    override fun refresh(): EntraToken {
        refreshFailure?.let { throw it }
        refreshCount++
        return issue()
    }

    override fun forget() {
        forgetCount++
    }

    private fun issue() = EntraToken(nextAccessToken, clock.instant().plus(validFor), user, tenantId)

    companion object {
        const val USER = "you@example.com"
        const val TENANT_ID = "00000000-0000-0000-0000-000000000000"
    }
}

class MutableClock(now: Instant = Instant.parse("2026-01-31T08:00:00Z")) : Clock() {

    // Read by the pool's own threads too, so a change has to reach them at once.
    @Volatile
    var now: Instant = now

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
