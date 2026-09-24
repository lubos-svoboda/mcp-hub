package cz.lubos.mcphub.database

import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EnvironmentRegistryTest {

    /** A database would silently ignore it, leaving TLS set up differently than the file suggests. */
    @Test
    fun `a certificate file on a database environment is refused with a pointer to the JDBC URL`() {
        val hubProperties = HubProperties(
            environments = mapOf(
                "POSTGRES_EXAMPLE_DB" to EnvironmentProperties(
                    description = "Example database",
                    type = EnvironmentType.POSTGRESQL,
                    url = "jdbc:postgresql://localhost:1/example",
                    username = "user",
                    password = "password",
                    caFile = "/config/certs/ca.pem",
                ),
            ),
        )

        val failure = assertThrows<IllegalArgumentException> { EnvironmentRegistry(hubProperties) }

        assertThat(failure.message).contains("POSTGRES_EXAMPLE_DB").contains("JDBC URL")
    }
}
