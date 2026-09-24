package cz.lubos.mcphub.grafana

import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GrafanaRegistryTest {

    @Test
    fun `a Grafana instance cannot sign in with Entra`() {
        val hubProperties = HubProperties(
            environments = mapOf(
                "GRAFANA_EXAMPLE" to EnvironmentProperties(
                    description = "Example Grafana",
                    type = EnvironmentType.GRAFANA,
                    url = "https://grafana.example.internal/",
                    token = "token",
                    authentication = AuthenticationMethod.ENTRA,
                ),
            ),
        )

        val failure = assertThrows<IllegalArgumentException> { GrafanaRegistry(hubProperties) }

        assertThat(failure.message).contains("GRAFANA_EXAMPLE").contains("token only")
    }
}
