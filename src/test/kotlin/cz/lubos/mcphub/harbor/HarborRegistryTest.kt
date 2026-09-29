package cz.lubos.mcphub.harbor

import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HarborRegistryTest {

    private val complete = EnvironmentProperties(
        description = "Container images",
        type = EnvironmentType.HARBOR,
        url = "https://harbor.example.internal/",
        username = "robot\$team+reader",
        token = "token",
        project = "team",
    )

    @Test
    fun `a complete Harbor environment becomes a registry and nothing else does`() {
        val registry = HarborRegistry(
            HubProperties(
                environments = mapOf(
                    "HARBOR_TEAM" to complete,
                    "GRAFANA_TEAM" to EnvironmentProperties("Dashboards", EnvironmentType.GRAFANA, "https://grafana.example.internal/", token = "t"),
                ),
            ),
        )

        assertThat(registry.names).containsExactly("HARBOR_TEAM")
        assertThat(registry.requireInstance("HARBOR_TEAM").project).isEqualTo("team")
    }

    @Test
    fun `the project, the robot account and its token are required`() {
        assertThat(failureFor(complete.copy(project = null))).contains("needs the project")
        assertThat(failureFor(complete.copy(username = null))).contains("needs the robot account")
        assertThat(failureFor(complete.copy(token = null))).contains("needs a token")
    }

    @Test
    fun `a writable Harbor environment is refused, because its tools only read`() {
        assertThat(failureFor(complete.copy(readOnly = false))).contains("whose tools only read")
    }

    @Test
    fun `an unknown registry is refused with the configured names`() {
        val registry = HarborRegistry(HubProperties(environments = mapOf("HARBOR_TEAM" to complete)))

        val failure = assertThrows<IllegalArgumentException> { registry.requireInstance("HARBOR_OTHER") }

        assertThat(failure.message).isEqualTo("Unknown Harbor registry 'HARBOR_OTHER'. Configured registries: HARBOR_TEAM.")
    }

    private fun failureFor(properties: EnvironmentProperties): String? =
        runCatching { HarborRegistry(HubProperties(environments = mapOf("HARBOR_TEAM" to properties))) }
            .exceptionOrNull()?.message
}
