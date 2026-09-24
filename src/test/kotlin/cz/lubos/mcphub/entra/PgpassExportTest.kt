package cz.lubos.mcphub.entra

import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.EntraAccountProperties
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.support.FakeEntraTokenClient
import cz.lubos.mcphub.support.FakeEntraTokenClient.Companion.TENANT_ID
import cz.lubos.mcphub.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class PgpassExportTest {

    @TempDir
    lateinit var directory: Path

    private val clock = MutableClock()
    private val tokenClient = FakeEntraTokenClient(clock)
    private val file by lazy { directory.resolve("pgpass.conf") }
    private val hubProperties by lazy {
        HubProperties(
            environments = mapOf(
                "AZURE_REPORTING_DB" to entraEnvironment("jdbc:postgresql://reporting.example.com:5432/reporting?sslmode=require"),
                "AZURE_ARCHIVE_DB" to entraEnvironment("jdbc:postgresql://reporting.example.com/archive"),
                "AZURE_BILLING_DB" to entraEnvironment("jdbc:postgresql://billing.example.com:6432/billing"),
            ),
            entraAccounts = mapOf("WORK" to EntraAccountProperties(tenantId = TENANT_ID, pgpassFile = file.toString())),
        )
    }
    private val accounts by lazy { EntraAccountRegistry(hubProperties, { tokenClient }, clock) }
    private val export by lazy { PgpassExport(hubProperties, accounts) }

    /** Two databases on one server and user share a line, and a missing port is the default one. */
    @Test
    fun `a sign-in writes one line per server and user`() {
        signIn()

        export.export("WORK")

        assertThat(Files.readAllLines(file)).containsExactly(
            "reporting.example.com:5432:*:app_readers:first-access-token",
            "billing.example.com:6432:*:app_readers:first-access-token",
        )
    }

    /** Nobody may be using the hub while the file is relied on, so the export itself renews the token. */
    @Test
    fun `a token about to expire is renewed and written without any database use`() {
        signIn()
        export.export("WORK")
        tokenClient.nextAccessToken = "second-access-token"
        clock.advance(Duration.ofMinutes(56))

        export.exportAll()

        assertThat(Files.readString(file)).contains(":second-access-token").doesNotContain("first-access-token")
    }

    @Test
    fun `an unchanged token leaves the file alone`() {
        signIn()
        export.export("WORK")
        Files.writeString(file, Files.readString(file) + "localhost:5432:*:postgres:added-meanwhile\n")

        export.export("WORK")

        assertThat(Files.readString(file)).contains("added-meanwhile")
    }

    /** A script regenerating the file from a template drops the hub's lines; the next export restores them. */
    @Test
    fun `lines another program removed come back on the next export`() {
        signIn()
        export.export("WORK")
        Files.writeString(file, "localhost:5432:*:postgres:secret\n")

        export.exportAll()

        assertThat(Files.readAllLines(file)).containsExactly(
            "reporting.example.com:5432:*:app_readers:first-access-token",
            "billing.example.com:6432:*:app_readers:first-access-token",
            "localhost:5432:*:postgres:secret",
        )
    }

    @Test
    fun `a URL naming several hosts is refused at startup with the reason`() {
        val properties = hubProperties.copy(
            environments = mapOf(
                "AZURE_REPLICATED_DB" to entraEnvironment("jdbc:postgresql://primary.example.com,replica.example.com/db"),
            ),
        )

        val failure = assertThrows<IllegalArgumentException> { PgpassExport(properties, accounts) }

        assertThat(failure.message).contains("AZURE_REPLICATED_DB").contains("one host")
    }

    @Test
    fun `signing out removes the lines`() {
        signIn()
        export.export("WORK")

        accounts.requireAccount("WORK").signOut()
        export.export("WORK")

        assertThat(Files.readString(file)).isEmpty()
    }

    @Test
    fun `a relative path is refused at startup`() {
        val properties = hubProperties.copy(
            entraAccounts = mapOf("WORK" to EntraAccountProperties(tenantId = TENANT_ID, pgpassFile = "pgpass.conf")),
        )

        val failure = assertThrows<IllegalArgumentException> { PgpassExport(properties, accounts) }

        assertThat(failure.message).contains("absolute pgpass-file")
    }

    private fun signIn() = accounts.requireAccount("WORK").completeSignIn("code", "http://localhost:8282/", "verifier")

    private fun entraEnvironment(url: String) = EnvironmentProperties(
        description = "Example database on Azure",
        type = EnvironmentType.POSTGRESQL,
        url = url,
        username = "app_readers",
        authentication = AuthenticationMethod.ENTRA,
        entraAccount = "WORK",
    )
}
