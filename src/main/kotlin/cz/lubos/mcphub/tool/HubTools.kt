package cz.lubos.mcphub.tool

import cz.lubos.mcphub.config.QueryFormat
import cz.lubos.mcphub.database.DatabaseEnvironment
import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.query.QueryLimits
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
            "or POSTGRESQL), Grafana instances (type GRAFANA) and Harbor registries (type HARBOR). Each entry carries its name, which " +
            "is the value every other tool expects as environment; a description of what it holds " +
            "and when to use it, which is the best guide to choosing between environments; the state (UP, " +
            "CONNECTING or DOWN) and since when it holds; the last error while DOWN; a note while UP but " +
            "lacking something the tools need, such as a Loki datasource; whether the " +
            "environment is read-only; and, for a database, how many pooled connections are in use. " +
            "An environment signing in with Microsoft Entra ID also carries entra: the account, whether it " +
            "is signed in, as whom and until when its token holds. A missing or expired sign-in can only be " +
            "completed by the user in a browser on the server's status page, never by a tool. " +
            "An environment whose database refused its password carries credentialRefusal: the server " +
            "stopped signing in so the account is not locked, and only the user can retry it, again on the " +
            "status page. " +
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
            "value, and the notes in the answer say whenever a cap cut something off. A shortened value " +
            "ends with …(+N), N being the characters left out. rows and textValueCharacters raise or " +
            "lower the caps up to the environment's ceilings; prefer LIMIT and a narrower select over " +
            "large values, since every row and character costs context. format toon returns the same " +
            "answer as compact text: the column names once, then one comma-separated line per row, with " +
            "values that contain a delimiter, are empty or read as null, true or false written as JSON " +
            "strings.",
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
        @McpToolParam(
            required = false,
            description = "Largest number of rows to return. The environment's default when left out, " +
                "500 unless configured otherwise; a value above its ceiling is lowered to the ceiling.",
        )
        rows: Int?,
        @McpToolParam(
            required = false,
            description = "Length a text value is shortened to. The environment's default when left out, " +
                "200 unless configured otherwise; 0 asks for the longest the environment allows.",
        )
        textValueCharacters: Int?,
        @McpToolParam(
            required = false,
            description = "json or toon, the more compact one. The environment's default when left out, json " +
                "unless configured otherwise.",
        )
        format: String?,
    ): Any {
        val databaseEnvironment = environmentRegistry.requireEnvironment(environment)
        val queryFormat = QueryFormat.requested(format, databaseEnvironment.settings.defaultFormat)
        val limits = QueryLimits.requested(databaseEnvironment.settings, rows, textValueCharacters)

        val response = try {
            respond(databaseEnvironment, queryRunner.run(databaseEnvironment, sql, limits), limits)
        } catch (failure: SQLException) {
            throw IllegalStateException(failureMessage(databaseEnvironment, failure), failure)
        }
        return if (queryFormat == QueryFormat.TOON) ToonEncoder.encode(response) else response
    }

    private fun respond(environment: DatabaseEnvironment, result: QueryResult, limits: QueryLimits): QueryResponse {
        val settings = environment.settings
        val notes = buildList {
            addAll(limits.cappedRequests)
            if (result.truncatedAtRowLimit) {
                val raise = if (limits.rows < settings.maxRows) "Raise rows (up to ${settings.maxRows}) or page" else "Page"
                add("Only the first ${limits.rows} rows are shown; more rows match this query. $raise the query.")
            }
            if (result.truncatedAtResponseSize) {
                add(
                    "Rows were cut off at the response size limit of ${settings.maxResponseCharacters} " +
                        "characters; more rows match this query.",
                )
            }
            if (result.shortenedColumns.isNotEmpty()) {
                add(
                    "Values in ${result.shortenedColumns.joinToString()} were shortened to " +
                        "${limits.textValueCharacters} characters, marked …(+N); raise textValueCharacters " +
                        "(up to ${settings.maxTextValueCharacters}; 0 asks for that ceiling).",
                )
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
