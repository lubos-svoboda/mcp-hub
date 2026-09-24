package cz.lubos.mcphub.entra

import cz.lubos.mcphub.config.EntraAccountProperties
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.support.FakeEntraTokenClient
import cz.lubos.mcphub.support.FakeEntraTokenClient.Companion.TENANT_ID
import cz.lubos.mcphub.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Duration

class EntraSignInTest {

    private val clock = MutableClock()
    private val tokenClient = FakeEntraTokenClient(clock)
    private val accounts = EntraAccountRegistry(
        HubProperties(entraAccounts = mapOf("WORK" to EntraAccountProperties(tenantId = TENANT_ID))),
        { tokenClient },
        clock,
    )
    private val signIn = EntraSignIn(accounts, clock)

    /** The verifier and challenge from the example in RFC 7636, appendix B. */
    @Test
    fun `the code challenge is the S256 hash the standard prescribes`() {
        assertThat(EntraSignIn.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun `an answer carrying the state of a started sign-in signs the account in with the matching verifier`() {
        val parameters = parameters(signIn.start("WORK", REDIRECT_URI))

        assertThat(signIn.complete(parameters.getValue("state"), "code-789")).isEqualTo("WORK")

        assertThat(accounts.requireAccount("WORK").status().state).isEqualTo(EntraAccountState.SIGNED_IN)
        assertThat(tokenClient.redeemedCode).isEqualTo("code-789")
        assertThat(EntraSignIn.codeChallenge(requireNotNull(tokenClient.redeemedCodeVerifier)))
            .isEqualTo(parameters.getValue("code_challenge"))
    }

    @Test
    fun `a state is good for one answer only`() {
        val state = parameters(signIn.start("WORK", REDIRECT_URI)).getValue("state")
        signIn.complete(state, "code")

        assertThrows<IllegalArgumentException> { signIn.complete(state, "code") }
    }

    @Test
    fun `an answer arriving after ten minutes is refused`() {
        val state = parameters(signIn.start("WORK", REDIRECT_URI)).getValue("state")
        clock.advance(Duration.ofMinutes(11))

        val failure = assertThrows<IllegalArgumentException> { signIn.complete(state, "code") }

        assertThat(failure.message).contains("10 minutes")
        assertThat(accounts.requireAccount("WORK").status().state).isEqualTo(EntraAccountState.SIGNED_OUT)
    }

    @Test
    fun `an answer this server never asked for is refused`() {
        assertThrows<IllegalArgumentException> { signIn.complete("made-up-state", "code") }
    }

    @Test
    fun `an error from Entra is kept as the account's last error`() {
        val state = parameters(signIn.start("WORK", REDIRECT_URI)).getValue("state")

        signIn.fail(state, "access_denied: AADSTS65004 The user declined to consent")

        assertThat(accounts.requireAccount("WORK").status().lastError).contains("AADSTS65004")
    }

    @Test
    fun `an unknown account cannot start a sign-in`() {
        val failure = assertThrows<IllegalArgumentException> { signIn.start("NO_SUCH_ACCOUNT", REDIRECT_URI) }

        assertThat(failure.message).contains("NO_SUCH_ACCOUNT").contains("WORK")
    }

    private fun parameters(url: URI): Map<String, String> =
        url.rawQuery.split('&').associate { parameter -> parameter.substringBefore('=') to parameter.substringAfter('=') }

    private companion object {
        const val REDIRECT_URI = "http://localhost:8282/"
    }
}
