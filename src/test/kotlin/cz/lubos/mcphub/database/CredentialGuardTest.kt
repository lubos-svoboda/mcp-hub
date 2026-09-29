package cz.lubos.mcphub.database

import cz.lubos.mcphub.config.EnvironmentType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource

class CredentialGuardTest {

    @Test
    fun `refused credentials are tried once and then no more`() {
        val database = CountingDatabase { throw SQLException("password authentication failed", "28P01") }
        val dataSource = CredentialGuard("POSTGRES_TEST_DB", EnvironmentType.POSTGRESQL).guard(database)

        val first = assertThrows<SQLException> { dataSource.connection }
        val later = (1..20).map { assertThrows<SQLException> { dataSource.connection } }

        assertThat(first.sqlState).isEqualTo("28P01")
        assertThat(later).allMatch { it is CredentialsRefusedException }
        assertThat(later.first().message).contains("POSTGRES_TEST_DB refused its credentials").contains("password authentication failed")
        assertThat(database.attempts.get()).isEqualTo(1)
    }

    @Test
    fun `an Oracle refusal is recognised by its error code, also below a wrapping exception`() {
        val database = CountingDatabase {
            throw SQLException("Connection failed", SQLException("ORA-01017: invalid username/password; logon denied", "72000", 1017))
        }
        val credentialGuard = CredentialGuard("ORACLE_TEST_DB", EnvironmentType.ORACLE)
        val dataSource = credentialGuard.guard(database)

        assertThrows<SQLException> { dataSource.connection }
        assertThrows<CredentialsRefusedException> { dataSource.connection }

        assertThat(credentialGuard.refusal()?.reason).isEqualTo("Connection failed")
        assertThat(database.attempts.get()).isEqualTo(1)
    }

    /** A network failure or a timeout does not lock an account, so the pool keeps retrying as before. */
    @Test
    fun `a failure other than refused credentials keeps signing in`() {
        val database = CountingDatabase { throw SQLException("Connection refused", "08001") }
        val credentialGuard = CredentialGuard("POSTGRES_TEST_DB", EnvironmentType.POSTGRESQL)
        val dataSource = credentialGuard.guard(database)

        repeat(3) { assertThrows<SQLException> { dataSource.connection } }

        assertThat(credentialGuard.refusal()).isNull()
        assertThat(database.attempts.get()).isEqualTo(3)
    }

    @Test
    fun `an explicit retry is exactly one more attempt`() {
        val database = CountingDatabase { throw SQLException("ORA-28000: The account is locked.", "72000", 28000) }
        val credentialGuard = CredentialGuard("ORACLE_TEST_DB", EnvironmentType.ORACLE)
        val dataSource = credentialGuard.guard(database)
        assertThrows<SQLException> { dataSource.connection }

        credentialGuard.allowOneAttempt()
        repeat(5) { assertThrows<SQLException> { dataSource.connection } }

        assertThat(database.attempts.get()).isEqualTo(2)
    }

    @Test
    fun `corrected credentials work again after the retry`() {
        var passwordCorrect = false
        val database = CountingDatabase {
            if (!passwordCorrect) throw SQLException("password authentication failed", "28P01")
            connection()
        }
        val credentialGuard = CredentialGuard("POSTGRES_TEST_DB", EnvironmentType.POSTGRESQL)
        val dataSource = credentialGuard.guard(database)
        assertThrows<SQLException> { dataSource.connection }

        passwordCorrect = true
        credentialGuard.allowOneAttempt()

        assertThat(dataSource.connection).isNotNull()
        assertThat(credentialGuard.refusal()).isNull()
    }

    /** The pool and the probe ask at the same moment right after a start; only one of them may sign in. */
    @Test
    fun `callers waiting before the first sign-in make a single attempt between them`() {
        val attemptStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val database = CountingDatabase {
            attemptStarted.countDown()
            release.await(10, TimeUnit.SECONDS)
            throw SQLException("password authentication failed", "28P01")
        }
        val dataSource = CredentialGuard("POSTGRES_TEST_DB", EnvironmentType.POSTGRESQL).guard(database)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val callers = (1..8).map { executor.submit { runCatching { dataSource.connection } } }
            attemptStarted.await(10, TimeUnit.SECONDS)
            release.countDown()
            callers.forEach { it.get(10, TimeUnit.SECONDS) }
        }

        assertThat(database.attempts.get()).isEqualTo(1)
    }

    private class CountingDatabase(private val signIn: () -> Connection) : DataSource {
        val attempts = AtomicInteger()

        override fun getConnection(): Connection {
            attempts.incrementAndGet()
            return signIn()
        }

        override fun getConnection(username: String?, password: String?): Connection = connection
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) {}
        override fun setLoginTimeout(seconds: Int) {}
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()
        override fun <T : Any?> unwrap(iface: Class<T>): T = throw SQLFeatureNotSupportedException()
        override fun isWrapperFor(iface: Class<*>): Boolean = false
    }

    private companion object {
        fun connection(): Connection =
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, _, _ -> null } as Connection
    }
}
