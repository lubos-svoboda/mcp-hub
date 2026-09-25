package cz.lubos.mcphub.grafana

import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient

/** No request is sent: the address is checked before anything leaves, token included. */
class GrafanaInstanceTest {

    private val instance = GrafanaInstance(
        settings = HubProperties(
            environments = mapOf(
                INSTANCE_NAME to EnvironmentProperties(
                    description = "Test instance",
                    type = EnvironmentType.GRAFANA,
                    url = BASE_URL,
                    token = "token",
                ),
            ),
        ).resolveSettings().getValue(INSTANCE_NAME),
        baseUrl = BASE_URL,
        token = "token",
        httpClient = HttpClient.newHttpClient(),
    )

    @Test
    fun `an endpoint is appended to the configured address and keeps its path`() {
        assertThat(instance.resolve("/api/search?query=orders"))
            .isEqualTo(URI("https://grafana.example.internal/grafana/api/search?query=orders"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["@evil.example/api/health", "//evil.example/api/health", ".evil.example/api", "api/health"])
    fun `an endpoint that could lead the token to another host is refused`(endpoint: String) {
        val failure = assertThrows<IllegalArgumentException> { instance.resolve(endpoint) }

        assertThat(failure.message).contains("single slash")
    }

    /** Nothing listens on port 1, and the HTTP client reports that with no message of its own. */
    @Test
    fun `an unreachable instance is reported with its address and the reason`() {
        val unreachableInstance = GrafanaInstance(
            settings = instance.settings,
            baseUrl = UNREACHABLE_URL,
            token = "token",
            httpClient = HttpClient.newHttpClient(),
        )

        val failure = assertThrows<IOException> { unreachableInstance.checkReachable() }

        // The causes below ConnectException differ between operating systems.
        assertThat(failure.message).startsWith("Grafana at $UNREACHABLE_URL could not be reached: ConnectException")
    }

    @Test
    fun `a refused token is down, whatever the health endpoint says`() {
        withGrafanaAnswering(401, """{"message":"Invalid API key"}""") { probedInstance ->
            val failure = assertThrows<IllegalStateException> { probedInstance.checkReachable() }

            assertThat(failure.message).isEqualTo("Grafana refused the token (HTTP 401): it may have been deleted or have expired.")
        }
    }

    @Test
    fun `a token seeing a Loki datasource is up without a note`() {
        withGrafanaAnswering(200, """[{"type":"prometheus"},{"type":"loki"}]""") { probedInstance ->
            assertThat(probedInstance.checkReachable()).isNull()
        }
    }

    /** grafana_api_request still works, so the instance stays up, but the log tools cannot. */
    @Test
    fun `a token seeing no Loki datasource is up with a note`() {
        withGrafanaAnswering(200, """[{"type":"prometheus"}]""") { probedInstance ->
            assertThat(probedInstance.checkReachable()).startsWith("No Loki datasource is visible to this token")
        }
    }

    @Test
    fun `a token that may not list datasources is up with a note`() {
        withGrafanaAnswering(403, """{"message":"Access denied"}""") { probedInstance ->
            assertThat(probedInstance.checkReachable()).contains("HTTP 403")
        }
    }

    /** Answers every request with one status and body, and only when the token comes along. */
    private fun withGrafanaAnswering(status: Int, body: String, check: (GrafanaInstance) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/datasources") { exchange ->
            val authorised = exchange.requestHeaders.getFirst("Authorization") == "Bearer token"
            val bytes = (if (authorised) body else "").toByteArray()
            exchange.sendResponseHeaders(if (authorised) status else 400, bytes.size.toLong().coerceAtLeast(-1))
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            check(
                GrafanaInstance(
                    settings = instance.settings,
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    token = "token",
                    httpClient = HttpClient.newHttpClient(),
                ),
            )
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        const val INSTANCE_NAME = "GRAFANA_TEST"
        const val BASE_URL = "https://grafana.example.internal/grafana/"
        const val UNREACHABLE_URL = "http://127.0.0.1:1"
    }
}
