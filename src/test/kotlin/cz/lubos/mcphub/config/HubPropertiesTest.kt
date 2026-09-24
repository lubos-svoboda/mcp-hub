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
            assertThat(settings.maxRows).isEqualTo(500)
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

                assertThat(settings.maxRows).isEqualTo(25)
                assertThat(settings.pool.maximumSize).isEqualTo(2)
                assertThat(settings.queryTimeoutSeconds).isEqualTo(60)
                assertThat(settings.pool.minimumIdle).isEqualTo(1)
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

    private fun org.springframework.context.ApplicationContext.resolvedSettings() =
        getBean(HubProperties::class.java).resolveSettings()

    @Configuration
    @EnableConfigurationProperties(HubProperties::class)
    class HubPropertiesConfiguration

    private companion object {
        const val PASSWORD = "s3cr3t-do-not-leak"
    }
}
