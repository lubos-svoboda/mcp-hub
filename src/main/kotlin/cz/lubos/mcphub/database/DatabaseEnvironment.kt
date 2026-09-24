package cz.lubos.mcphub.database

import com.zaxxer.hikari.HikariDataSource
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.target.ProbeTarget
import cz.lubos.mcphub.config.EnvironmentType
import java.sql.SQLException

class DatabaseEnvironment(
    val settings: EnvironmentSettings,
    val dataSource: HikariDataSource,
) : ProbeTarget, AutoCloseable {

    override val name: String get() = settings.name
    override val description: String get() = settings.description
    override val type: EnvironmentType get() = settings.type
    override val readOnly: Boolean get() = settings.readOnly

    override fun checkReachable() {
        val validationTimeoutSeconds = settings.pool.validationTimeoutSeconds.toInt()
        dataSource.connection.use { connection ->
            if (!connection.isValid(validationTimeoutSeconds)) {
                throw SQLException("Connection did not answer within $validationTimeoutSeconds s")
            }
        }
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
