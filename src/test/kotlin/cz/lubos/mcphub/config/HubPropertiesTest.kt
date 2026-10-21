package cz.lubos.mcphub.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class HubPropertiesTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(HubPropertiesConfiguration::class.java)
        .withPropertyValues(
            "mcp-hub.environments.ORACLE_TEST_DB.description=Test environment",
            "mcp-hub.environments.ORACLE_TEST_DB.type=ORACLE",
            "mcp-hub.environments.ORACLE_TEST_DB.url=jdbc:oracle:thin:@//localhost:1521/freepdb1",
            "mcp-hub.environments.ORACLE_TEST_DB.username=hub",
            "mcp-hub.environments.ORACLE_TEST_DB.password=$PASSWORD",
        )

    @Test
    fun `an environment without overrides inherits every default`() {
        contextRunner.run { context ->
            val settings = context.resolvedSettings().getValue("ORACLE_TEST_DB")

            assertThat(settings.name).isEqualTo("ORACLE_TEST_DB")
            assertThat(settings.description).isEqualTo("Test environment")
            assertThat(settings.readOnly).isTrue()
            assertThat(settings.defaultRows).isEqualTo(500)
            assertThat(settings.maxRows).isEqualTo(5_000)
            assertThat(settings.defaultTextValueCharacters).isEqualTo(200)
            assertThat(settings.maxTextValueCharacters).isEqualTo(4_000)
            assertThat(settings.queryTimeoutSeconds).isEqualTo(60)
            assertThat(settings.connectTimeoutSeconds).isEqualTo(10)
            assertThat(settings.socketReadTimeoutSeconds).isEqualTo(120)
            assertThat(settings.pool.maximumSize).isEqualTo(5)
            assertThat(settings.pool.keepaliveSeconds).isEqualTo(120)
        }
    }

    @Test
    fun `an override wins over the default and leaves the other values alone`() {
        contextRunner
            .withPropertyValues(
                "mcp-hub.environments.ORACLE_TEST_DB.max-rows=25",
                "mcp-hub.environments.ORACLE_TEST_DB.pool.maximum-size=2",
            )
            .run { context ->
                val settings = context.resolvedSettings().getValue("ORACLE_TEST_DB")

                assertThat(settings.defaultRows).isEqualTo(25)
                assertThat(settings.maxRows).isEqualTo(25)
                assertThat(settings.pool.maximumSize).isEqualTo(2)
                assertThat(settings.queryTimeoutSeconds).isEqualTo(60)
                assertThat(settings.pool.minimumIdle).isEqualTo(1)
            }
    }

    /** Before default-rows existed, max-rows was the default; such a configuration keeps its meaning. */
    @Test
    fun `a default above the ceiling is lowered to it`() {
        contextRunner
            .withPropertyValues(
                "mcp-hub.defaults.default-rows=500",
                "mcp-hub.environments.ORACLE_TEST_DB.max-rows=100",
                "mcp-hub.environments.ORACLE_TEST_DB.max-text-value-characters=50",
            )
            .run { context ->
                val settings = context.resolvedSettings().getValue("ORACLE_TEST_DB")

                assertThat(settings.defaultRows).isEqualTo(100)
                assertThat(settings.defaultTextValueCharacters).isEqualTo(50)
            }
    }

    @Test
    fun `the default format is json and is set in lower case for all environments or one`() {
        contextRunner.run { context ->
            assertThat(context.resolvedSettings().getValue("ORACLE_TEST_DB").defaultFormat).isEqualTo(QueryFormat.JSON)
        }
        contextRunner
            .withPropertyValues("mcp-hub.defaults.default-format=toon")
            .run { context ->
                assertThat(context.resolvedSettings().getValue("ORACLE_TEST_DB").defaultFormat).isEqualTo(QueryFormat.TOON)
            }
        contextRunner
            .withPropertyValues(
                "mcp-hub.defaults.default-format=toon",
                "mcp-hub.environments.ORACLE_TEST_DB.default-format=json",
            )
            .run { context ->
                assertThat(context.resolvedSettings().getValue("ORACLE_TEST_DB").defaultFormat).isEqualTo(QueryFormat.JSON)
            }
    }

    @Test
    fun `a call's format wins over the default, whatever its case`() {
        assertThat(QueryFormat.requested(null, QueryFormat.TOON)).isEqualTo(QueryFormat.TOON)
        assertThat(QueryFormat.requested("JSON", QueryFormat.TOON)).isEqualTo(QueryFormat.JSON)
        assertThat(QueryFormat.requested("toon", QueryFormat.JSON)).isEqualTo(QueryFormat.TOON)
        assertThat(runCatching { QueryFormat.requested("csv", QueryFormat.JSON) }.exceptionOrNull())
            .hasMessage("format must be json or toon, not csv.")
    }

    @Test
    fun `a default and a ceiling are set on their own`() {
        contextRunner
            .withPropertyValues(
                "mcp-hub.environments.ORACLE_TEST_DB.default-rows=100",
                "mcp-hub.environments.ORACLE_TEST_DB.max-rows=500",
            )
            .run { context ->
                val settings = context.resolvedSettings().getValue("ORACLE_TEST_DB")

                assertThat(settings.defaultRows).isEqualTo(100)
                assertThat(settings.maxRows).isEqualTo(500)
            }
    }

    @Test
    fun `neither resolved settings nor raw properties render the password`() {
        contextRunner.run { context ->
            val properties = context.getBean(HubProperties::class.java)

            assertThat(properties.resolveSettings().getValue("ORACLE_TEST_DB").toString())
                .doesNotContain(PASSWORD)
            assertThat(properties.environments.getValue("ORACLE_TEST_DB").toString())
                .doesNotContain(PASSWORD)
        }
    }

    @Test
    fun `an Entra account and an environment signing in with it bind from their configuration keys`() {
        contextRunner
            .withPropertyValues(
                "mcp-hub.entra-accounts.WORK.tenant-id=00000000-0000-0000-0000-000000000000",
                "mcp-hub.entra-accounts.WORK.expected-user=you@example.com",
                "mcp-hub.environments.AZURE_TEST_DB.description=Azure environment",
                "mcp-hub.environments.AZURE_TEST_DB.type=POSTGRESQL",
                "mcp-hub.environments.AZURE_TEST_DB.url=jdbc:postgresql://example.postgres.database.azure.com/example",
                "mcp-hub.environments.AZURE_TEST_DB.username=app_readers",
                "mcp-hub.environments.AZURE_TEST_DB.authentication=ENTRA",
                "mcp-hub.environments.AZURE_TEST_DB.entra-account=WORK",
            )
            .run { context ->
                val properties = context.getBean(HubProperties::class.java)
                val account = properties.entraAccounts.getValue("WORK")

                assertThat(account.tenantId).isEqualTo("00000000-0000-0000-0000-000000000000")
                assertThat(account.expectedUser).isEqualTo("you@example.com")
                assertThat(account.clientId).isEqualTo(EntraAccountProperties.AZURE_CLI_CLIENT_ID)
                assertThat(properties.environments.getValue("AZURE_TEST_DB").authentication)
                    .isEqualTo(AuthenticationMethod.ENTRA)
                assertThat(properties.resolveSettings().getValue("AZURE_TEST_DB").entraAccount).isEqualTo("WORK")
                assertThat(properties.resolveSettings().getValue("ORACLE_TEST_DB").entraAccount).isNull()
            }
    }

    private fun org.springframework.context.ApplicationContext.resolvedSettings() =
        getBean(HubProperties::class.java).resolveSettings()

    @Configuration
    @EnableConfigurationProperties(HubProperties::class)
    class HubPropertiesConfiguration

    private companion object {
        const val PASSWORD = "s3cr3t-do-not-leak"
    }
}
