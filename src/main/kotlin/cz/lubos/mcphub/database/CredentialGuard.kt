package cz.lubos.mcphub.database

import cz.lubos.mcphub.config.EnvironmentType
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLInvalidAuthorizationSpecException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock

data class CredentialRefusal(
    val refusedAt: Instant,
    val reason: String,
)

class CredentialsRefusedException(message: String) : SQLInvalidAuthorizationSpecException(message)

/**
 * Stops signing in once the database refuses the credentials. The pool keeps asking for
 * connections and the probe checks every few seconds, so without this a wrong password would use
 * up the account's failed login limit within a minute and lock it for everyone who shares it.
 */
class CredentialGuard(
    private val environmentName: String,
    private val type: EnvironmentType,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Volatile
    private var refusal: CredentialRefusal? = null

    private val attemptLock = ReentrantLock()

    fun refusal(): CredentialRefusal? = refusal

    /** Fails without contacting the database while the credentials stand refused. */
    fun requireNotRefused() {
        refusal?.let { throw CredentialsRefusedException(describe(it)) }
    }

    /** Called on an explicit request only, so every retry of refused credentials is one attempt someone asked for. */
    fun allowOneAttempt() {
        attemptLock.withLock { refusal = null }
    }

    fun guard(dataSource: DataSource): DataSource = GuardedDataSource(dataSource, this)

    /**
     * One sign-in at a time, so a wrong password costs one failed attempt, not one per waiting thread.
     * The pool opens connections one by one anyway, so nothing waits here in normal operation.
     */
    internal fun connect(signIn: () -> Connection): Connection = attemptLock.withLock {
        requireNotRefused()
        try {
            signIn()
        } catch (failure: SQLException) {
            if (isCredentialRefusal(failure)) {
                refusal = CredentialRefusal(clock.instant(), failure.message ?: failure.javaClass.simpleName)
            }
            throw failure
        }
    }

    private fun isCredentialRefusal(failure: SQLException): Boolean =
        generateSequence(failure as Throwable) { it.cause }
            .filterIsInstance<SQLException>()
            .any { exception ->
                when (type) {
                    EnvironmentType.ORACLE -> exception.errorCode in ORACLE_REFUSALS
                    EnvironmentType.POSTGRESQL -> exception.sqlState in POSTGRES_REFUSALS
                    EnvironmentType.GRAFANA, EnvironmentType.HARBOR -> false
                }
            }

    private fun describe(refusal: CredentialRefusal) =
        "Environment $environmentName refused its credentials at ${refusal.refusedAt}: ${refusal.reason}. " +
            "The hub stopped signing in so that the account is not locked. Correct the credentials and " +
            "restart the hub, or press Check at this environment on the status page to try once."

    private companion object {
        /** ORA-01017 invalid username or password, ORA-28000 account locked, ORA-28001 password expired. */
        val ORACLE_REFUSALS = setOf(1017, 28000, 28001)

        /** 28P01 invalid password. 28000 is left out: it also covers pg_hba refusing the client address, which no retry locks. */
        val POSTGRES_REFUSALS = setOf("28P01")
    }
}

private class GuardedDataSource(
    private val delegate: DataSource,
    private val credentialGuard: CredentialGuard,
) : DataSource by delegate {

    override fun getConnection(): Connection = credentialGuard.connect { delegate.connection }

    override fun getConnection(username: String?, password: String?): Connection =
        credentialGuard.connect { delegate.getConnection(username, password) }
}
