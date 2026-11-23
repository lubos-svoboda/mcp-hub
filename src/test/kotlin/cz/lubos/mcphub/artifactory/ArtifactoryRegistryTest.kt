package cz.lubos.mcphub.artifactory

import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ArtifactoryRegistryTest {

    private val complete = EnvironmentProperties(
        description = "Docker images and Maven artifacts",
        type = EnvironmentType.ARTIFACTORY,
        url = "https://artifactory.example.internal/",
        token = "token",
    )

    @Test
    fun `a complete Artifactory environment becomes an instance and nothing else does`() {
        val registry = ArtifactoryRegistry(
            HubProperties(
                environments = mapOf(
                    "ARTIFACTORY" to complete,
                    "HARBOR_TEAM" to complete.copy(type = EnvironmentType.HARBOR, username = "robot\$team+reader", project = "team"),
                ),
            ),
        )

        assertThat(registry.names).containsExactly("ARTIFACTORY")
    }

    @Test
    fun `the token is required`() {
        assertThat(failureFor(complete.copy(token = null))).contains("needs a token")
    }

    @Test
    fun `a writable Artifactory environment is refused, because its tools only read`() {
        assertThat(failureFor(complete.copy(readOnly = false))).contains("whose tools only read")
    }

    @Test
    fun `an Artifactory environment cannot sign in with Microsoft Entra ID`() {
        assertThat(failureFor(complete.copy(authentication = AuthenticationMethod.ENTRA, entraAccount = "WORK")))
            .contains("signs in with its own token only, not with Microsoft Entra ID")
    }

    @Test
    fun `a username or project, which nothing would enforce, is refused`() {
        assertThat(failureFor(complete.copy(username = "reader"))).contains("takes neither username nor project")
        assertThat(failureFor(complete.copy(project = "crisp"))).contains("takes neither username nor project")
    }

    @Test
    fun `an unknown instance is refused with the configured names`() {
        val registry = ArtifactoryRegistry(HubProperties(environments = mapOf("ARTIFACTORY" to complete)))

        val failure = assertThrows<IllegalArgumentException> { registry.requireInstance("ARTIFACTORY_OTHER") }

        assertThat(failure.message).isEqualTo("Unknown Artifactory instance 'ARTIFACTORY_OTHER'. Configured instances: ARTIFACTORY.")
    }

    private fun failureFor(properties: EnvironmentProperties): String? =
        runCatching { ArtifactoryRegistry(HubProperties(environments = mapOf("ARTIFACTORY" to properties))) }
            .exceptionOrNull()?.message
}
