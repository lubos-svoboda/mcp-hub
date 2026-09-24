package cz.lubos.mcphub.status

import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.EntraAccountProperties
import cz.lubos.mcphub.config.EnvironmentDefaults
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.config.PoolDefaults
import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.entra.EntraAccountRegistry
import cz.lubos.mcphub.entra.EntraAccountState
import cz.lubos.mcphub.support.FakeEntraTokenClient
import cz.lubos.mcphub.support.FakeEntraTokenClient.Companion.TENANT_ID
import cz.lubos.mcphub.support.FakeEntraTokenClient.Companion.USER
import cz.lubos.mcphub.support.MutableClock
import cz.lubos.mcphub.target.ConnectionProbe
import cz.lubos.mcphub.target.ProbeTarget
import cz.lubos.mcphub.target.TargetRegistry
import cz.lubos.mcphub.support.awaitIdle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StatusControllerTest {

    private val clock = MutableClock()
    private val tokenClient = FakeEntraTokenClient(clock).apply { nextAccessToken = ACCESS_TOKEN }
    private val hubProperties = HubProperties(
        defaults = EnvironmentDefaults(pool = PoolDefaults(minimumIdle = 0)),
        environments = mapOf(
            "AZURE_EXAMPLE_DB" to EnvironmentProperties(
                description = "Example database on Azure",
                type = EnvironmentType.POSTGRESQL,
                url = "jdbc:postgresql://127.0.0.1:1/example",
                username = "app_readers",
                authentication = AuthenticationMethod.ENTRA,
                entraAccount = "WORK",
            ),
        ),
        entraAccounts = mapOf("WORK" to EntraAccountProperties(tenantId = TENANT_ID)),
    )
    private val accounts = EntraAccountRegistry(hubProperties, { tokenClient }, clock)
    private val environmentRegistry = EnvironmentRegistry(hubProperties, accounts)
    private val connectionProbe = ConnectionProbe(listOf(environmentRegistry), hubProperties)
    private val reporter = EnvironmentStatusReporter(listOf(environmentRegistry), connectionProbe, accounts)
    private val controller = StatusController(reporter, connectionProbe)

    @AfterEach
    fun close() = environmentRegistry.close()

    @Test
    fun `an account nobody signed in to offers the sign-in`() {
        val page = controller.statusAsPage()

        assertThat(page).contains("<h2>Entra accounts</h2>")
        assertThat(page).contains("""<a class="button" href="/entra/sign-in/WORK">Sign in</a>""")
        assertThat(page).contains("Entra WORK")
        assertThat(page).contains("""<div class="banner">Entra account <strong>WORK</strong> is not signed in, so 1""")
    }

    @Test
    fun `a signed-in account leaves no banner`() {
        accounts.requireAccount("WORK").completeSignIn("code", "http://localhost:8282/", "verifier")

        assertThat(controller.statusAsPage()).doesNotContain("""<div class="banner">""")
    }

    @Test
    fun `a signed-in account shows who signed in and offers the sign-out, never the token`() {
        accounts.requireAccount("WORK").completeSignIn("code", "http://localhost:8282/", "verifier")

        val page = controller.statusAsPage()
        val environment = controller.statusAsJson().single()

        assertThat(page).contains(USER).contains("""action="/entra/sign-out/WORK"""")
        assertThat(environment.entra?.state).isEqualTo(EntraAccountState.SIGNED_IN)
        assertThat(environment.entra?.user).isEqualTo(USER)
        assertThat(page).doesNotContain(ACCESS_TOKEN)
        assertThat(environment.toString()).doesNotContain(ACCESS_TOKEN)
    }

    @Test
    fun `a page without Entra accounts has no Entra section`() {
        val plainProbe = ConnectionProbe(emptyList(), HubProperties())
        val plainReporter = EnvironmentStatusReporter(
            emptyList(),
            plainProbe,
            EntraAccountRegistry(HubProperties(), { tokenClient }, clock),
        )

        assertThat(StatusController(plainReporter, plainProbe).statusAsPage()).doesNotContain("Entra accounts")
    }

    /** Right after a sign-in or a click on Check now the outcome is seconds away, not a whole reload. */
    @Test
    fun `a running check shows a spinner and reloads the page quickly until it is done`() {
        val release = CountDownLatch(1)
        val slowTarget = object : ProbeTarget {
            override val name = "SLOW_DB"
            override val description = "Answers when released"
            override val type = EnvironmentType.POSTGRESQL
            override val readOnly = true
            override fun checkReachable() {
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val registry = object : TargetRegistry {
            override fun targets(): Collection<ProbeTarget> = listOf(slowTarget)
        }
        val probe = ConnectionProbe(listOf(registry), HubProperties())
        val page = StatusController(EnvironmentStatusReporter(listOf(registry), probe, accounts), probe)

        probe.probeAllInBackground()
        val whileChecking = page.statusAsPage()
        release.countDown()
        probe.awaitIdle()
        val afterwards = page.statusAsPage()

        assertThat(whileChecking).contains("Checking environments").contains("""content="2"""")
        assertThat(afterwards).contains("""Checked <time datetime="20""").contains("countdown").contains("""content="10"""").doesNotContain("spinner\"")
    }

    private companion object {
        const val ACCESS_TOKEN = "eyJ-secret-access-token"
    }
}
