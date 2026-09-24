package cz.lubos.mcphub.query

import cz.lubos.mcphub.database.DatabaseEnvironment
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.SQLException

/**
 * [affectedRows] is whatever the driver reported, never a nicer number invented here. Null means
 * the driver gave no count at all; note that drivers disagree on DDL, where PostgreSQL answers 0.
 */
data class WriteResult(
    val environment: String,
    val affectedRows: Int?,
)

/**
 * The only place that commits. Reading goes through [ReadOnlySession], which never commits, so
 * the two paths cannot be confused for one another.
 */
@Component
class WriteStatementRunner {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    fun execute(environment: DatabaseEnvironment, sql: String): WriteResult {
        val settings = environment.settings
        require(!settings.readOnly) {
            "Environment ${settings.name} is read-only, so nothing can be written to it."
        }

        return environment.openConnection().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.queryTimeout = settings.queryTimeoutSeconds
                    if (statement.execute(sql)) {
                        connection.rollback()
                        throw IllegalArgumentException(
                            "The statement returned rows, so it is a query. Use run_sql_query for that.",
                        )
                    }
                    val affectedRows = statement.updateCount.takeIf { it >= 0 }
                    connection.commit()

                    // The only trace a write leaves behind, so it is logged whole.
                    logger.info("Wrote to {}: {} row(s) affected by [{}]", settings.name, affectedRows, sql)
                    WriteResult(environment = settings.name, affectedRows = affectedRows)
                }
            } catch (failure: SQLException) {
                connection.rollback()
                logger.warn("Write to {} failed and was rolled back: {}", settings.name, failure.message)
                throw failure
            }
        }
    }
}
