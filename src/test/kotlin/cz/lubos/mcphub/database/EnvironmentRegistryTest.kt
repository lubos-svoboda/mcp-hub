package cz.lubos.mcphub.database

import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.EntraAccountProperties
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

    @Test
    fun `an Oracle environment cannot sign in with Entra`() {
        val failure = refused(
            entraEnvironment().copy(type = EnvironmentType.ORACLE, url = "jdbc:oracle:thin:@//localhost:1/example"),
        )

        assertThat(failure.message).contains("ENTRA_EXAMPLE_DB").contains("Only POSTGRESQL")
    }

    @Test
    fun `an Entra environment without an account is refused with the accounts that exist`() {
        val failure = refused(entraEnvironment().copy(entraAccount = null))

        assertThat(failure.message).contains("needs entra-account").contains(ACCOUNT_NAME)
    }

    @Test
    fun `an Entra environment naming an undefined account is refused`() {
        val failure = refused(entraEnvironment().copy(entraAccount = "NO_SUCH_ACCOUNT"))

        assertThat(failure.message).contains("NO_SUCH_ACCOUNT").contains("not defined")
    }

    /** A password next to a token would be ignored, and the file would claim a way in that is not used. */
    @Test
    fun `an Entra environment with a password is refused`() {
        val failure = refused(entraEnvironment().copy(password = "password"))

        assertThat(failure.message).contains("takes no password")
    }

    @Test
    fun `an account named by a password environment is refused rather than ignored`() {
        val failure = refused(entraEnvironment().copy(authentication = AuthenticationMethod.PASSWORD, password = "password"))

        assertThat(failure.message).contains("authentication: ENTRA")
    }

    private fun refused(environment: EnvironmentProperties) = assertThrows<IllegalArgumentException> {
        EnvironmentRegistry(
            HubProperties(
                environments = mapOf("ENTRA_EXAMPLE_DB" to environment),
                entraAccounts = mapOf(ACCOUNT_NAME to EntraAccountProperties(tenantId = TENANT_ID)),
            ),
        )
    }

    private fun entraEnvironment() = EnvironmentProperties(
        description = "Example database on Azure",
        type = EnvironmentType.POSTGRESQL,
        url = "jdbc:postgresql://example.postgres.database.azure.com:5432/example?sslmode=require",
        username = "app_readers",
        authentication = AuthenticationMethod.ENTRA,
        entraAccount = ACCOUNT_NAME,
    )

    private companion object {
        const val ACCOUNT_NAME = "WORK"
        const val TENANT_ID = "00000000-0000-0000-0000-000000000000"
    }
}
