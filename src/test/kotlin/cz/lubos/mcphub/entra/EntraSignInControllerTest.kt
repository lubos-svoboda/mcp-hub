package cz.lubos.mcphub.entra

import cz.lubos.mcphub.config.EntraAccountProperties
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.status.EnvironmentStatusReporter
import cz.lubos.mcphub.status.StatusController
import cz.lubos.mcphub.support.FakeEntraTokenClient
import cz.lubos.mcphub.support.FakeEntraTokenClient.Companion.TENANT_ID
import cz.lubos.mcphub.support.MutableClock
import cz.lubos.mcphub.target.ConnectionProbe
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.net.URI

/** The routing matters: Entra answers on the root, which otherwise only leads to the status page. */
class EntraSignInControllerTest {

    private val clock = MutableClock()
    private val tokenClient = RecordingTokenClient(FakeEntraTokenClient(clock))
    private val hubProperties = HubProperties(entraAccounts = mapOf("WORK" to EntraAccountProperties(tenantId = TENANT_ID)))
    private val accounts = EntraAccountRegistry(hubProperties, { tokenClient }, clock)
    private val environmentRegistry = EnvironmentRegistry(hubProperties, accounts)
    private val connectionProbe = ConnectionProbe(listOf(environmentRegistry), hubProperties)
    private val mockMvc = MockMvcBuilders.standaloneSetup(
        EntraSignInController(EntraSignIn(accounts, clock), accounts, environmentRegistry, connectionProbe),
        StatusController(EnvironmentStatusReporter(listOf(environmentRegistry), connectionProbe)),
    ).build()

    @AfterEach
    fun close() = environmentRegistry.close()

    @Test
    fun `a sign-in sends the browser to Entra with the port it came in on`() {
        mockMvc.get("/entra/sign-in/WORK") { header(HttpHeaders.HOST, "127.0.0.1:9000") }
            .andExpect { status { isFound() } }

        assertThat(tokenClient.lastRedirectUri).isEqualTo("http://localhost:9000/")
    }

    @Test
    fun `the answer on the root completes the sign-in and returns to the status page`() {
        val state = startSignIn()

        mockMvc.get("/") { param("code", "code-123"); param("state", state) }
            .andExpect {
                status { isSeeOther() }
                header { string(HttpHeaders.LOCATION, "/status") }
            }

        assertThat(accounts.requireAccount("WORK").status().state).isEqualTo(EntraAccountState.SIGNED_IN)
    }

    @Test
    fun `the root without a state still leads to the status page`() {
        mockMvc.get("/").andExpect {
            status { isFound() }
            header { string(HttpHeaders.LOCATION, "/status") }
        }
    }

    @Test
    fun `an answer with an unknown state is refused with a reason`() {
        mockMvc.get("/") { param("code", "code-123"); param("state", "made-up") }
            .andExpect {
                status { isBadRequest() }
                content { string(org.hamcrest.Matchers.containsString("Start it again on the status page")) }
            }
    }

    @Test
    fun `an error answer is shown on the account`() {
        val state = startSignIn()

        mockMvc.get("/") { param("state", state); param("error", "access_denied"); param("error_description", "declined") }
            .andExpect { status { isSeeOther() } }

        assertThat(accounts.requireAccount("WORK").status().lastError).isEqualTo("Sign-in failed: access_denied: declined")
    }

    @Test
    fun `signing out ends the sign-in`() {
        mockMvc.get("/") { param("code", "code-123"); param("state", startSignIn()) }

        mockMvc.post("/entra/sign-out/WORK").andExpect { status { isSeeOther() } }

        assertThat(accounts.requireAccount("WORK").status().state).isEqualTo(EntraAccountState.SIGNED_OUT)
    }

    @Test
    fun `an unknown account is refused with the accounts that exist`() {
        mockMvc.get("/entra/sign-in/NO_SUCH_ACCOUNT").andExpect {
            status { isBadRequest() }
            content { string(org.hamcrest.Matchers.containsString("Configured accounts: WORK")) }
        }
    }

    private fun startSignIn(): String {
        val location = mockMvc.get("/entra/sign-in/WORK") { header(HttpHeaders.HOST, "localhost:8282") }
            .andReturn().response.getHeader(HttpHeaders.LOCATION)
        return URI(location).rawQuery.split('&').first { it.startsWith("state=") }.substringAfter('=')
    }

    private class RecordingTokenClient(private val delegate: FakeEntraTokenClient) : EntraTokenClient by delegate {
        var lastRedirectUri: String? = null

        override fun authorizationUrl(redirectUri: String, state: String, codeChallenge: String, loginHint: String?): URI {
            lastRedirectUri = redirectUri
            return delegate.authorizationUrl(redirectUri, state, codeChallenge, loginHint)
        }
    }
}
