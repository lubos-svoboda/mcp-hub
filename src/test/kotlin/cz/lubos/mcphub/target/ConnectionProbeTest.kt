package cz.lubos.mcphub.target

import cz.lubos.mcphub.config.EnvironmentDefaults
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.config.PoolDefaults
import cz.lubos.mcphub.config.ProbeProperties
import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.support.awaitIdle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test
    fun `a slow target does not hold up the others`() {
        val fastTargetChecked = CountDownLatch(1)
        // Listed first: probed one after another, it would wait for a check that never comes.
        val slowTarget = StubTarget("SLOW_DB") {
            check(fastTargetChecked.await(5, TimeUnit.SECONDS)) { "The fast target was not probed meanwhile" }
        }
        val fastTarget = StubTarget("FAST_DB") { fastTargetChecked.countDown() }
        val registry = object : TargetRegistry {
            override fun targets(): Collection<ProbeTarget> = listOf(slowTarget, fastTarget)
        }
        val probe = ConnectionProbe(listOf(registry), hubProperties)

        probe.probeAll()

        assertThat(probe.statusOf("SLOW_DB")?.connectionState).isEqualTo(ConnectionState.UP)
        assertThat(probe.statusOf("FAST_DB")?.connectionState).isEqualTo(ConnectionState.UP)
    }

    @Test
    fun `a single environment can be checked without the others`() {
        val registry = object : TargetRegistry {
            override fun targets(): Collection<ProbeTarget> = listOf(StubTarget("FIRST_DB") {}, StubTarget("SECOND_DB") {})
        }
        val probe = ConnectionProbe(listOf(registry), hubProperties)

        probe.probeInBackground("FIRST_DB")
        probe.awaitIdle()

        assertThat(probe.statusOf("FIRST_DB")?.connectionState).isEqualTo(ConnectionState.UP)
        assertThat(probe.statusOf("SECOND_DB")?.connectionState).isEqualTo(ConnectionState.CONNECTING)
        assertThrows<IllegalArgumentException> { probe.probeInBackground("NO_SUCH_DB") }
    }

    private class StubTarget(override val name: String, private val check: () -> Unit) : ProbeTarget {
        override val description: String = "Stub"
        override val type: EnvironmentType = EnvironmentType.POSTGRESQL
        override val readOnly: Boolean = true

        override fun checkReachable(): String? {
            check()
            return null
        }
    }

    private companion object {
        const val ENVIRONMENT_NAME = "ORACLE_UNREACHABLE_DB"
        const val PASSWORD = "s3cr3t-do-not-leak"
    }
}
