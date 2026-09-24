package cz.lubos.mcphub.query

import cz.lubos.mcphub.database.DatabaseEnvironment
import org.springframework.stereotype.Component
import java.sql.Connection

/**
 * The one place that opens a read-only transaction. Everything reading from a database goes
 * through here, so the guarantee cannot be forgotten in a new caller.
 */
@Component
class ReadOnlySession {

    fun <T> execute(environment: DatabaseEnvironment, work: (Connection) -> T): T =
        environment.openConnection().use { connection ->
            connection.autoCommit = false
            try {
                // SET TRANSACTION must be the first statement of its transaction, so whatever the
                // connection may have started before being handed over is ended first.
                connection.rollback()
                connection.createStatement().use { statement ->
                    statement.execute("SET TRANSACTION READ ONLY")
                }
                work(connection)
            } finally {
                // A read-only transaction is ended, never committed.
                connection.rollback()
            }
        }
}
