package cz.lubos.mcphub.entra

import cz.lubos.mcphub.config.EntraAccountProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Only the address the browser is sent to can be checked without a directory to sign in to. Entra
 * compares the redirect URI with the application's registration character by character, and the
 * Azure CLI registers http://localhost with no path, so the exact form matters.
 */
class MsalEntraTokenClientTest {

    private val client = MsalEntraTokenClient(EntraAccountProperties(tenantId = TENANT_ID))

    @Test
    fun `the sign-in address carries the tenant, the Azure CLI client, PKCE and the database scope`() {
        val url = client.authorizationUrl(
            redirectUri = "http://localhost:8282/",
            state = "state-123",
            codeChallenge = "challenge-456",
            loginHint = "you@example.com",
        )
        val parameters = url.rawQuery.split('&').associate { parameter ->
            val (name, value) = parameter.split('=', limit = 2)
            name to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

        assertThat("${url.scheme}://${url.host}${url.path}")
            .isEqualTo("https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/authorize")
        assertThat(parameters["client_id"]).isEqualTo(EntraAccountProperties.AZURE_CLI_CLIENT_ID)
        assertThat(parameters["redirect_uri"]).isEqualTo("http://localhost:8282/")
        assertThat(parameters["response_type"]).isEqualTo("code")
        assertThat(parameters["state"]).isEqualTo("state-123")
        assertThat(parameters["code_challenge"]).isEqualTo("challenge-456")
        assertThat(parameters["code_challenge_method"]).isEqualTo("S256")
        assertThat(parameters["login_hint"]).isEqualTo("you@example.com")
        // The answer arrives as a POST to the root, which is where the server listens for it.
        assertThat(parameters["response_mode"]).isEqualTo("form_post")
        // offline_access is what makes Entra hand out a refresh token at all.
        assertThat(parameters["scope"]?.split(' '))
            .contains("https://ossrdbms-aad.database.windows.net/.default", "offline_access")
    }

    @Test
    fun `a refresh before any sign-in asks for one`() {
        assertThrows<EntraSignInRequiredException> { client.refresh() }
    }

    private companion object {
        const val TENANT_ID = "00000000-0000-0000-0000-000000000000"
    }
}
