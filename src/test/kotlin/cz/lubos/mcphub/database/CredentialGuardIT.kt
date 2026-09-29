package cz.lubos.mcphub.database

import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.support.OracleTestDatabase
import cz.lubos.mcphub.support.PostgresTestDatabase
import cz.lubos.mcphub.target.ConnectionProbe
import cz.lubos.mcphub.target.ConnectionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The refusals the guard stops at must be the ones the real drivers raise; a made-up exception in
 * a unit test would pass with any error code. The hub cannot count the database's failed logins,
 * so the proof that no further attempt is made is that the later errors come from the guard itself.
 */
@Tag("integration")
class CredentialGuardIT {

    /** The driver's own wording differs between versions; the error code the guard matches does not. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "ORACLE_TEST_DB, ORA-01017",
        "POSTGRES_TEST_DB, password authentication failed",
    )
    fun `a wrong password stops the signing in and the probe reports why`(environmentName: String, driverReason: String) {
        val hubProperties = withWrongPassword(
            if (environmentName == OracleTestDatabase.ENVIRONMENT_NAME) OracleTestDatabase.hubProperties() else PostgresTestDatabase.hubProperties(),
        )
        EnvironmentRegistry(hubProperties).use { registry ->
            val probe = ConnectionProbe(listOf(registry), hubProperties)
            val environment = requireNotNull(registry.find(environmentName))

            repeat(3) { probe.probeAll() }

            assertThat(environment.credentialRefusal()?.reason).contains(driverReason)
            assertThat(probe.statusOf(environmentName)?.connectionState).isEqualTo(ConnectionState.DOWN)
            assertThat(probe.statusOf(environmentName)?.lastError)
                .startsWith("Environment $environmentName refused its credentials")
                .contains(driverReason)
                .contains("stopped signing in")
        }
    }

    private fun withWrongPassword(hubProperties: HubProperties) = hubProperties.copy(
        environments = hubProperties.environments.mapValues { (_, properties) -> properties.copy(password = "wrong-password") },
    )
}
