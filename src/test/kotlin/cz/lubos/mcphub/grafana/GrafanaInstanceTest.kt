package cz.lubos.mcphub.grafana

import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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

    private companion object {
        const val INSTANCE_NAME = "GRAFANA_TEST"
        const val BASE_URL = "https://grafana.example.internal/grafana/"
    }
}
