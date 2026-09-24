package cz.lubos.mcphub.target

import cz.lubos.mcphub.config.EnvironmentDefaults
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.config.PoolDefaults
import cz.lubos.mcphub.config.ProbeProperties
import cz.lubos.mcphub.database.EnvironmentRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Nothing listens on port 1, so every attempt fails immediately and the probe can be
 * exercised without a database.
 */
class ConnectionProbeTest {

    private val hubProperties = HubProperties(
        probe = ProbeProperties(intervalSeconds = 60),
        defaults = EnvironmentDefaults(
            connectTimeoutSeconds = 1,
            pool = PoolDefaults(maximumSize = 1, minimumIdle = 0, validationTimeoutSeconds = 1),
        ),
        environments = mapOf(
            ENVIRONMENT_NAME to EnvironmentProperties(
                description = "Nothing is listening here",
                type = EnvironmentType.ORACLE,
                url = "jdbc:oracle:thin:@//127.0.0.1:1/unreachable",
                username = "hub",
                password = PASSWORD,
            ),
        ),
    )

    @Test
    fun `an environment is reported as connecting until the first probe runs`() {
        EnvironmentRegistry(hubProperties).use { registry ->
            val status = ConnectionProbe(listOf(registry), hubProperties).statusOf(ENVIRONMENT_NAME)

            assertThat(status?.connectionState).isEqualTo(ConnectionState.CONNECTING)
        }
    }

    @Test
    fun `an unreachable environment goes down with a reason and keeps the time it started`() {
        EnvironmentRegistry(hubProperties).use { registry ->
            val probe = ConnectionProbe(listOf(registry), hubProperties)

            probe.probeAll()
            val afterFirstProbe = probe.statusOf(ENVIRONMENT_NAME)
            probe.probeAll()
            val afterSecondProbe = probe.statusOf(ENVIRONMENT_NAME)

            assertThat(afterFirstProbe?.connectionState).isEqualTo(ConnectionState.DOWN)
            assertThat(afterFirstProbe?.lastError).contains("127.0.0.1")
            assertThat(afterSecondProbe?.connectionState).isEqualTo(ConnectionState.DOWN)

            // The outage started once; a later probe must not move its start time forward.
            assertThat(afterSecondProbe?.since).isEqualTo(afterFirstProbe?.since)
        }
    }

    @Test
    fun `a failure reason never carries the password`() {
        EnvironmentRegistry(hubProperties).use { registry ->
            val probe = ConnectionProbe(listOf(registry), hubProperties)
            probe.probeAll()

            assertThat(probe.statusOf(ENVIRONMENT_NAME)?.lastError).doesNotContain(PASSWORD)
        }
    }

    private companion object {
        const val ENVIRONMENT_NAME = "ORACLE_UNREACHABLE_DB"
        const val PASSWORD = "s3cr3t-do-not-leak"
    }
}
