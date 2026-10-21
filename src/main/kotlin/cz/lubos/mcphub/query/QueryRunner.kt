package cz.lubos.mcphub.query

import cz.lubos.mcphub.database.DatabaseEnvironment
import org.springframework.stereotype.Component

@Component
class QueryRunner(
    private val readOnlySession: ReadOnlySession,
    private val resultMapper: ResultMapper,
) {

    fun run(
        environment: DatabaseEnvironment,
        sql: String,
        limits: QueryLimits = QueryLimits.defaultsOf(environment.settings),
    ): QueryResult {
        val settings = environment.settings
        return readOnlySession.execute(environment) { connection ->
            connection.createStatement().use { statement ->
                // One row beyond the limit is allowed so that its presence proves the result
                // is incomplete; ResultMapper discards it.
                statement.maxRows = limits.rows + 1
                statement.queryTimeout = settings.queryTimeoutSeconds
                require(statement.execute(sql)) {
                    "The statement produced no result set, but a query returning rows is expected."
                }
                statement.resultSet.use { resultSet -> resultMapper.map(resultSet, limits, settings.maxResponseCharacters) }
            }
        }
    }
}
