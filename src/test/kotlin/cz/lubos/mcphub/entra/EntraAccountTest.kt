package cz.lubos.mcphub.entra

import cz.lubos.mcphub.config.EntraAccountProperties
import cz.lubos.mcphub.support.FakeEntraTokenClient
import cz.lubos.mcphub.support.FakeEntraTokenClient.Companion.TENANT_ID
import cz.lubos.mcphub.support.FakeEntraTokenClient.Companion.USER
import cz.lubos.mcphub.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.time.Duration

class EntraAccountTest {

    private val clock = MutableClock()
    private val tokenClient = FakeEntraTokenClient(clock)

    @Test
    fun `an account nobody signed in to refuses at once and says where to sign in`() {
        val account = account()

        val failure = assertThrows<EntraSignInRequiredException> { account.accessToken() }

        assertThat(failure.message).isEqualTo("Entra account WORK is not signed in; sign in on the status page.")
        assertThat(account.status().state).isEqualTo(EntraAccountState.SIGNED_OUT)
    }

    @Test
    fun `a sign-in records who signed in and until when the token holds`() {
        val account = account()

        account.completeSignIn("code", REDIRECT_URI, "verifier")

        val status = account.status()
        assertThat(status.state).isEqualTo(EntraAccountState.SIGNED_IN)
        assertThat(status.user).isEqualTo(USER)
        assertThat(status.signedInSince).isEqualTo(clock.now)
        assertThat(status.accessTokenValidUntil).isEqualTo(clock.now.plus(Duration.ofMinutes(60)))
        assertThat(tokenClient.redeemedCodeVerifier).isEqualTo("verifier")
    }

    @Test
    fun `a fresh token is reused and one about to expire is renewed`() {
        val account = signedIn()

        clock.advance(Duration.ofMinutes(50))
        assertThat(account.accessToken().accessToken).isEqualTo("first-access-token")
        assertThat(tokenClient.refreshCount).isZero()

        tokenClient.nextAccessToken = "second-access-token"
        clock.advance(Duration.ofMinutes(6))
        assertThat(account.accessToken().accessToken).isEqualTo("second-access-token")
        assertThat(tokenClient.refreshCount).isEqualTo(1)
        assertThat(account.status().lastRefresh).isEqualTo(clock.now)
        assertThat(account.status().accessTokenValidUntil).isEqualTo(clock.now.plus(Duration.ofMinutes(60)))
    }

    @Test
    fun `a refused refresh token asks for a new sign-in and keeps the reason`() {
        val account = signedIn()
        tokenClient.refreshFailure = EntraSignInRequiredException("Entra asks for a new sign-in (invalid_grant): expired")
        clock.advance(Duration.ofMinutes(58))

        val failure = assertThrows<EntraSignInRequiredException> { account.accessToken() }

        assertThat(failure.message).startsWith("Entra account WORK must sign in again on the status page.")
        assertThat(failure.message).contains("invalid_grant")
        assertThat(account.status().state).isEqualTo(EntraAccountState.SIGN_IN_REQUIRED)
        assertThrows<EntraSignInRequiredException> { account.requireSignedIn() }
    }

    /** Entra may be out of reach for a moment; that alone must not cut off an account whose token still holds. */
    @Test
    fun `an unreachable Entra keeps the valid token in use and fails only once it has expired`() {
        val account = signedIn()
        tokenClient.refreshFailure = IOException("Connection reset")
        clock.advance(Duration.ofMinutes(57))

        assertThat(account.accessToken().accessToken).isEqualTo("first-access-token")
        assertThat(account.status().state).isEqualTo(EntraAccountState.SIGNED_IN)
        assertThat(account.status().lastError).contains("Connection reset")

        clock.advance(Duration.ofMinutes(4))
        val failure = assertThrows<IllegalStateException> { account.accessToken() }
        assertThat(failure.message).contains("expired access token")
    }

    @Test
    fun `a sign-in into another tenant is refused and forgotten`() {
        val account = account()
        tokenClient.tenantId = "11111111-1111-1111-1111-111111111111"

        val failure = assertThrows<IllegalArgumentException> { account.completeSignIn("code", REDIRECT_URI, "verifier") }

        assertThat(failure.message).contains("tenant 11111111-1111-1111-1111-111111111111")
        assertThat(account.status().state).isEqualTo(EntraAccountState.SIGNED_OUT)
        assertThat(tokenClient.forgetCount).isEqualTo(1)
        assertThrows<EntraSignInRequiredException> { account.accessToken() }
    }

    @Test
    fun `only the expected user may sign in, whatever the case of the name`() {
        val account = account(expectedUser = "You@Example.com")
        account.completeSignIn("code", REDIRECT_URI, "verifier")
        assertThat(account.status().state).isEqualTo(EntraAccountState.SIGNED_IN)

        tokenClient.user = "someone.else@example.com"
        val failure = assertThrows<IllegalArgumentException> { account.completeSignIn("code", REDIRECT_URI, "verifier") }

        assertThat(failure.message).contains("someone.else@example.com").contains("You@Example.com")
        assertThat(account.status().state).isEqualTo(EntraAccountState.SIGNED_OUT)
    }

    @Test
    fun `signing out forgets the tokens`() {
        val account = signedIn()

        account.signOut()

        assertThat(account.status().state).isEqualTo(EntraAccountState.SIGNED_OUT)
        assertThat(tokenClient.forgetCount).isEqualTo(1)
        assertThrows<EntraSignInRequiredException> { account.accessToken() }
    }

    @Test
    fun `neither the token nor the status renders the access token`() {
        val account = signedIn()

        assertThat(account.accessToken().toString()).doesNotContain("first-access-token")
        assertThat(account.status().toString()).doesNotContain("first-access-token")
    }

    private fun signedIn() = account().also { it.completeSignIn("code", REDIRECT_URI, "verifier") }

    private fun account(expectedUser: String? = null) =
        EntraAccount("WORK", EntraAccountProperties(tenantId = TENANT_ID, expectedUser = expectedUser), tokenClient, clock)

    private companion object {
        const val REDIRECT_URI = "http://localhost:8282/"
    }
}
