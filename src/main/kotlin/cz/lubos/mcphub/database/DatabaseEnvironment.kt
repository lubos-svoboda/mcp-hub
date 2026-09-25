package cz.lubos.mcphub.database

import com.zaxxer.hikari.HikariDataSource
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.entra.EntraAccount
import cz.lubos.mcphub.target.ProbeTarget
import cz.lubos.mcphub.config.EnvironmentType
import java.sql.Connection
import java.sql.SQLException

class DatabaseEnvironment(
    val settings: EnvironmentSettings,
    val dataSource: HikariDataSource,
    /** Set when the environment signs in with Microsoft Entra ID instead of a password. */
    val entraAccount: EntraAccount? = null,
) : ProbeTarget, AutoCloseable {

    override val name: String get() = settings.name
    override val description: String get() = settings.description
    override val type: EnvironmentType get() = settings.type
    override val readOnly: Boolean get() = settings.readOnly

    override fun checkReachable(): String? {
        val validationTimeoutSeconds = settings.pool.validationTimeoutSeconds.toInt()
        openConnection().use { connection ->
            if (!connection.isValid(validationTimeoutSeconds)) {
                throw SQLException("Connection did not answer within $validationTimeoutSeconds s")
            }
        }
        return null
    }

    /** The one way to a connection, so that a missing Entra sign-in fails at once rather than after the pool timeout. */
    fun openConnection(): Connection {
        entraAccount?.requireSignedIn()
        return dataSource.connection
    }

    fun poolUsage(): PoolUsage? =
        dataSource.hikariPoolMXBean?.let { poolMXBean ->
            PoolUsage(
                active = poolMXBean.activeConnections,
                idle = poolMXBean.idleConnections,
                total = poolMXBean.totalConnections,
            )
        }

    override fun close() = dataSource.close()
}

data class PoolUsage(
    val active: Int,
    val idle: Int,
    val total: Int,
)
