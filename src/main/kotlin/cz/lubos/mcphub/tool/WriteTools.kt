package cz.lubos.mcphub.tool

import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.query.WriteResult
import cz.lubos.mcphub.query.WriteStatementRunner
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * Kept apart from the reading tools on purpose: writing is a different act, and a caller has to
 * reach for a differently named tool to do it.
 */
@Component
class WriteTools(
    private val environmentRegistry: EnvironmentRegistry,
    private val writeStatementRunner: WriteStatementRunner,
) {

    @McpTool(
        name = "execute_write_statement",
        description = "Runs one statement that changes data or structure — INSERT, UPDATE, DELETE, " +
            "MERGE or DDL — and commits it. Only environments that list_environments reports as " +
            "not read-only accept it; every other environment refuses before anything reaches the " +
            "database. The change is permanent: unlike run_sql_query, nothing is rolled back " +
            "afterwards. A failing statement is rolled back and its error returned. " +
            "A statement that returns rows is refused; run_sql_query is the tool for queries. " +
            "The answer carries the number of affected rows as the database driver reported it, " +
            "which for DDL is usually 0 or missing.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false,
            openWorldHint = true,
        ),
    )
    fun executeWriteStatement(
        @McpToolParam(required = true, description = DATABASE_ENVIRONMENT)
        environment: String,
        @McpToolParam(
            required = true,
            description = "One statement in the dialect of the environment's database. Leave out the " +
                "semicolon after a plain SQL statement, which Oracle refuses; a PL/SQL block keeps its " +
                "closing end;.",
        )
        sql: String,
    ): WriteResult {
        val databaseEnvironment = environmentRegistry.requireEnvironment(environment)
        if (databaseEnvironment.settings.readOnly) {
            throw IllegalArgumentException(writableEnvironmentsMessage(environment))
        }
        return writeStatementRunner.execute(databaseEnvironment, sql)
    }

    private fun writableEnvironmentsMessage(requestedName: String): String {
        val writableNames = environmentRegistry.all()
            .filterNot { it.settings.readOnly }
            .map { it.name }
            .sorted()
        if (writableNames.isEmpty()) {
            return "Environment $requestedName is read-only, and so is every other one on this server."
        }
        return "Environment $requestedName is read-only. Writable environments: ${writableNames.joinToString()}."
    }
}
