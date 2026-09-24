package cz.lubos.mcphub.database

import cz.lubos.mcphub.query.QueryRunner
import cz.lubos.mcphub.query.ReadOnlySession
import cz.lubos.mcphub.query.ResultMapper
import cz.lubos.mcphub.support.OracleTestDatabase
import cz.lubos.mcphub.support.OracleTestDatabase.ENVIRONMENT_NAME
import cz.lubos.mcphub.target.ConnectionProbe
import cz.lubos.mcphub.target.ConnectionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The point of the whole server: an outage is reported with a reason and the connection comes
 * back on its own, without anything being restarted or rebuilt.
 */
@Tag("integration")
class ConnectionLifecycleIT {

    private val hubProperties = OracleTestDatabase.hubProperties()

    @Test
    fun `an environment goes down with the database and recovers by itself when it returns`() {
        EnvironmentRegistry(hubProperties).use { registry ->
            val probe = ConnectionProbe(listOf(registry), hubProperties)

            probe.probeAll()
            assertThat(probe.statusOf(ENVIRONMENT_NAME)?.connectionState).isEqualTo(ConnectionState.UP)
            val wentUpAt = probe.statusOf(ENVIRONMENT_NAME)?.since

            OracleTestDatabase.stopDatabase()
            probe.probeAll()
            val whileDown = probe.statusOf(ENVIRONMENT_NAME)
            assertThat(whileDown?.connectionState).isEqualTo(ConnectionState.DOWN)
            assertThat(whileDown?.lastError).isNotBlank()
            assertThat(whileDown?.since).isNotEqualTo(wentUpAt)

            OracleTestDatabase.startDatabase()
            probeUntilUp(probe)

            assertThat(probe.statusOf(ENVIRONMENT_NAME)?.connectionState).isEqualTo(ConnectionState.UP)

            // Same registry, same pool, same probe — nothing was recreated in between.
            val environment = requireNotNull(registry.find(ENVIRONMENT_NAME))
            val result = QueryRunner(ReadOnlySession(), ResultMapper()).run(environment, "select 1 as one from dual")
            assertThat(result.rows).containsExactly(listOf("1"))
        }
    }

    private fun probeUntilUp(probe: ConnectionProbe) {
        val deadline = Instant.now().plusSeconds(240)
        while (Instant.now() < deadline) {
            probe.probeAll()
            if (probe.statusOf(ENVIRONMENT_NAME)?.connectionState == ConnectionState.UP) {
                return
            }
            Thread.sleep(2_000)
        }
    }
}
