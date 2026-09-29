package cz.lubos.mcphub.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.StandardEnvironment
import java.nio.file.Files
import java.nio.file.Path

/** The image imports secrets.env the way the Dockerfile sets it, so a restart reads the file again. */
class SecretsFileImportTest {

    @TempDir
    lateinit var configDirectory: Path

    @Test
    fun `a secrets file saved on Windows is read with its values intact`() {
        Files.writeString(
            configDirectory.resolve("secrets.env"),
            "# comment\r\nORACLE_TEST_PASSWORD=pa:ss#word!\r\nGRAFANA_TEST_TOKEN=glsa_token\r\n",
        )

        val environment = importSecrets()

        assertThat(environment.getProperty("ORACLE_TEST_PASSWORD")).isEqualTo("pa:ss#word!")
        assertThat(environment.getProperty("GRAFANA_TEST_TOKEN")).isEqualTo("glsa_token")
    }

    @Test
    fun `a missing secrets file leaves the start to the environment variables`() {
        assertThat(importSecrets().getProperty("ORACLE_TEST_PASSWORD")).isNull()
    }

    private fun importSecrets(): StandardEnvironment {
        val environment = StandardEnvironment()
        System.setProperty(IMPORT_PROPERTY, "optional:${configDirectory.resolve("secrets.env").toUri()}[.properties]")
        try {
            ConfigDataEnvironmentPostProcessor.applyTo(environment)
        } finally {
            System.clearProperty(IMPORT_PROPERTY)
        }
        return environment
    }

    private companion object {
        const val IMPORT_PROPERTY = "spring.config.import"
    }
}
