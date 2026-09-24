package cz.lubos.mcphub.tool

import cz.lubos.mcphub.database.DatabaseEnvironment
import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.query.QueryResult
import cz.lubos.mcphub.query.QueryRunner
import cz.lubos.mcphub.status.EnvironmentStatusReporter
import cz.lubos.mcphub.status.EnvironmentView
import cz.lubos.mcphub.target.ConnectionProbe
import cz.lubos.mcphub.target.ConnectionState
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.sql.SQLException

data class QueryResponse(
    val environment: String,
    val readOnly: Boolean,
    val columns: List<String>,
    val rows: List<List<String?>>,
    val rowCount: Int,
    val notes: List<String>,
)

@Component
class HubTools(
    private val environmentStatusReporter: EnvironmentStatusReporter,
    private val environmentRegistry: EnvironmentRegistry,
    private val connectionProbe: ConnectionProbe,
    private val queryRunner: QueryRunner,
) {

    @McpTool(
        name = "list_environments",
        description = "Lists every environment this server is connected to: databases (type ORACLE " +
            "or POSTGRESQL) and Grafana instances (type GRAFANA). Each entry carries its name, which " +
            "is the value every other tool expects as environment; a description of what it holds " +
            "and when to use it, which is the best guide to choosing between environments; the state (UP, " +
            "CONNECTING or DOWN) and since when it holds; the last error while DOWN; whether the " +
            "environment is read-only; and, for a database, how many pooled connections are in use. " +
            "Start here when unsure which environments exist or why one fails.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false,
        ),
    )
    fun listEnvironments(): List<EnvironmentView> = environmentStatusReporter.report()

    @McpTool(
        name = "run_sql_query",
        description = "Runs one SQL query against a database environment and returns the rows. The " +
            "answer lists the column names once and every row as an array of values in the same " +
            "order. Every value is text: numbers keep their full precision, dates and timestamps are " +
            "ISO-8601, binary values are summarised by their size, and null stays null. " +
            "The statement always runs inside a read-only transaction that is rolled back " +
            "afterwards, on every environment, so anything that writes is refused by the database " +
            "itself; execute_write_statement is the tool for changing data. " +
            "Results are capped by row count, by overall size and by the length of a single text " +
            "value, and the notes in the answer say whenever a cap cut something off.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun runSqlQuery(
        @McpToolParam(required = true, description = DATABASE_ENVIRONMENT)
        environment: String,
        @McpToolParam(
            required = true,
            description = "One SQL statement returning rows, in the dialect of the environment's " +
                "database. Oracle refuses a trailing semicolon, so leave it out.",
        )
        sql: String,
    ): QueryResponse {
        val databaseEnvironment = environmentRegistry.requireEnvironment(environment)

        return try {
            respond(databaseEnvironment, queryRunner.run(databaseEnvironment, sql))
        } catch (failure: SQLException) {
            throw IllegalStateException(failureMessage(databaseEnvironment, failure), failure)
        }
    }

    private fun respond(environment: DatabaseEnvironment, result: QueryResult): QueryResponse {
        val settings = environment.settings
        val notes = buildList {
            if (result.truncatedAtRowLimit) {
                add("Only the first ${settings.maxRows} rows are shown; more rows match this query.")
            }
            if (result.truncatedAtResponseSize) {
                add(
                    "Rows were cut off at the response size limit of ${settings.maxResponseCharacters} " +
                        "characters; more rows match this query.",
                )
            }
            if (result.truncatedTextValues) {
                add("At least one text value was shortened to ${settings.maxTextValueCharacters} characters.")
            }
        }
        return QueryResponse(
            environment = environment.name,
            readOnly = settings.readOnly,
            columns = result.columns,
            rows = result.rows,
            rowCount = result.rows.size,
            notes = notes,
        )
    }

    /**
     * A query is attempted even when the environment was last seen as down, because it may have
     * recovered since the last probe. The remembered state is only used to explain a failure.
     */
    private fun failureMessage(environment: DatabaseEnvironment, failure: SQLException): String {
        val reason = failure.message ?: failure.javaClass.simpleName
        val status = connectionProbe.statusOf(environment.name)
        if (status == null || status.connectionState != ConnectionState.DOWN) {
            return "Query on ${environment.name} failed: $reason"
        }
        return "Query on ${environment.name} failed: $reason. " +
            "The environment has been unreachable since ${status.since}: ${status.lastError}"
    }
}
