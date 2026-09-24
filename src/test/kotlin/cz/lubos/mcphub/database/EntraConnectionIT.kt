package cz.lubos.mcphub.database

import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.EntraAccountProperties
import cz.lubos.mcphub.config.EnvironmentDefaults
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.config.PoolDefaults
import cz.lubos.mcphub.config.ProbeProperties
import cz.lubos.mcphub.entra.EntraAccountRegistry
import cz.lubos.mcphub.entra.EntraSignInRequiredException
import cz.lubos.mcphub.query.QueryRunner
import cz.lubos.mcphub.query.ReadOnlySession
import cz.lubos.mcphub.query.ResultMapper
import cz.lubos.mcphub.support.FakeEntraTokenClient
import cz.lubos.mcphub.support.FakeEntraTokenClient.Companion.TENANT_ID
import cz.lubos.mcphub.support.FakeEntraTokenClient.Companion.USER
import cz.lubos.mcphub.support.MutableClock
import cz.lubos.mcphub.support.PostgresTestDatabase
import cz.lubos.mcphub.target.ConnectionProbe
import cz.lubos.mcphub.target.ConnectionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.DriverManager
import java.time.Duration

/**
 * Entra itself cannot take part, so the database accepts the stand-in's token as the password of a
 * role. What is left untested is only the conversation with Entra ID; the README says so.
 */
@Tag("integration")
class EntraConnectionIT {

    private val clock = MutableClock()
    private val tokenClient = FakeEntraTokenClient(clock)
    private val queryRunner = QueryRunner(ReadOnlySession(), ResultMapper())

    @BeforeEach
    fun `the role accepts the first token`() {
        administer(
            "do ${'$'}${'$'} begin if not exists (select from pg_roles where rolname = '$ROLE') then " +
                "create role $ROLE login; end if; end ${'$'}${'$'}",
            "alter role $ROLE password '${tokenClient.nextAccessToken}'",
            "grant select on all tables in schema public to $ROLE",
        )
    }

    @Test
    fun `an environment is down until the account signs in, then up`() {
        withRegistry { registry, entraAccounts ->
            val probe = ConnectionProbe(listOf(registry), hubProperties)
            val environment = registry.requireEnvironment(ENVIRONMENT_NAME)

            val startedAt = System.nanoTime()
            probe.probeAll()
            val probeMillis = (System.nanoTime() - startedAt) / 1_000_000

            assertThat(probe.statusOf(ENVIRONMENT_NAME)?.connectionState).isEqualTo(ConnectionState.DOWN)
            assertThat(probe.statusOf(ENVIRONMENT_NAME)?.lastError).contains("not signed in")
            // The pool's own timeout would take connect-timeout-seconds; a missing sign-in is known at once.
            assertThat(probeMillis).isLessThan(2_000)
            assertThrows<EntraSignInRequiredException> { queryRunner.run(environment, "select 1") }

            entraAccounts.requireAccount(ACCOUNT_NAME).completeSignIn("code", "http://localhost:8282/", "verifier")
            probe.probeAll()

            assertThat(probe.statusOf(ENVIRONMENT_NAME)?.connectionState).isEqualTo(ConnectionState.UP)
        }
    }

    /** The database only knows the group it was reached as; the connection has to say who is behind it. */
    @Test
    fun `a connection signs in as the configured role and is tagged with exactly the signed-in user`() {
        withSignedInEnvironment { environment ->
            val row = queryRunner.run(
                environment,
                "select current_user, application_name from pg_stat_activity where pid = pg_backend_pid()",
            ).rows.single()

            assertThat(row).containsExactly(ROLE, "mcp-hub/$USER")
        }
    }

    @Test
    fun `a renewed token signs in new connections while an established one keeps working`() {
        withSignedInEnvironment { environment ->
            environment.openConnection().use { establishedConnection ->
                administer("alter role $ROLE password 'second-access-token'")
                tokenClient.nextAccessToken = "second-access-token"
                clock.advance(Duration.ofMinutes(56))
                environment.dataSource.hikariPoolMXBean?.softEvictConnections()

                val result = queryRunner.run(environment, "select current_user")

                assertThat(result.rows.single().single()).isEqualTo(ROLE)
                assertThat(tokenClient.refreshCount).isEqualTo(1)
                assertThat(establishedConnection.isValid(3)).isTrue()
            }
        }
    }

    @Test
    fun `after signing out nothing connects any more`() {
        withRegistry { registry, entraAccounts ->
            val account = entraAccounts.requireAccount(ACCOUNT_NAME)
            account.completeSignIn("code", "http://localhost:8282/", "verifier")
            val environment = registry.requireEnvironment(ENVIRONMENT_NAME)
            queryRunner.run(environment, "select 1")

            account.signOut()
            registry.evictConnectionsOf(ACCOUNT_NAME)

            assertThrows<EntraSignInRequiredException> { queryRunner.run(environment, "select 1") }
            assertThat(environment.poolUsage()?.idle).isZero()
        }
    }

    private fun withSignedInEnvironment(work: (DatabaseEnvironment) -> Unit) =
        withRegistry { registry, entraAccounts ->
            entraAccounts.requireAccount(ACCOUNT_NAME).completeSignIn("code", "http://localhost:8282/", "verifier")
            work(registry.requireEnvironment(ENVIRONMENT_NAME))
        }

    private fun withRegistry(work: (EnvironmentRegistry, EntraAccountRegistry) -> Unit) {
        val entraAccounts = EntraAccountRegistry(hubProperties, { tokenClient }, clock)
        EnvironmentRegistry(hubProperties, entraAccounts).use { registry -> work(registry, entraAccounts) }
    }

    private fun administer(vararg statements: String) =
        DriverManager.getConnection(PostgresTestDatabase.jdbcUrl, PostgresTestDatabase.USERNAME, PostgresTestDatabase.PASSWORD)
            .use { connection -> connection.createStatement().use { statement -> statements.forEach(statement::execute) } }

    private val hubProperties = HubProperties(
        probe = ProbeProperties(intervalSeconds = 3_600),
        defaults = EnvironmentDefaults(
            connectTimeoutSeconds = 5,
            pool = PoolDefaults(maximumSize = 2, minimumIdle = 0, validationTimeoutSeconds = 3),
        ),
        environments = mapOf(
            ENVIRONMENT_NAME to EnvironmentProperties(
                description = "Integration test database reached with an Entra token",
                type = EnvironmentType.POSTGRESQL,
                url = PostgresTestDatabase.jdbcUrl,
                username = ROLE,
                authentication = AuthenticationMethod.ENTRA,
                entraAccount = ACCOUNT_NAME,
            ),
        ),
        entraAccounts = mapOf(ACCOUNT_NAME to EntraAccountProperties(tenantId = TENANT_ID)),
    )

    private companion object {
        const val ENVIRONMENT_NAME = "POSTGRES_ENTRA_TEST_DB"
        const val ACCOUNT_NAME = "WORK"
        const val ROLE = "app_readers"
    }
}
