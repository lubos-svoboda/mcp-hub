package cz.lubos.mcphub.tool

import cz.lubos.mcphub.catalog.CatalogReader
import cz.lubos.mcphub.catalog.ObjectSource
import cz.lubos.mcphub.catalog.QueryPlan
import cz.lubos.mcphub.catalog.SchemaMatch
import cz.lubos.mcphub.catalog.SchemaObjectKind
import cz.lubos.mcphub.catalog.TableDescription
import cz.lubos.mcphub.database.EnvironmentRegistry
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class CatalogTools(
    private val environmentRegistry: EnvironmentRegistry,
    private val catalogReader: CatalogReader,
) {

    @McpTool(
        name = "describe_table",
        description = "Describes one or more tables in one call. For each table the answer gives its " +
            "owner (the schema), its columns in definition order with the type written the way DDL " +
            "writes it — for example VARCHAR2(100 CHAR) or character varying(400) — and whether " +
            "they accept null, its primary key, unique, foreign key and named check constraints " +
            "with their columns, and its indexes. Names are matched regardless of case. A table " +
            "that does not exist or is not visible to the account is left out of the answer, and a " +
            "name found in several schemas appears once per schema unless the environment is " +
            "limited to one schema.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun describeTable(
        @McpToolParam(required = true, description = DATABASE_ENVIRONMENT)
        environment: String,
        @McpToolParam(required = true, description = "Table names, without a schema prefix. Case does not matter.")
        tables: List<String>,
    ): List<TableDescription> {
        require(tables.isNotEmpty()) { "At least one table name is expected." }
        return catalogReader.describeTables(environmentRegistry.requireEnvironment(environment), tables)
    }

    @McpTool(
        name = "search_schema",
        description = "Finds tables, views, columns and stored programs whose name contains the given " +
            "text, regardless of case. Views include materialized views. Stored programs are Oracle " +
            "packages, procedures, functions, triggers and types, or PostgreSQL functions and " +
            "procedures. Each hit carries its kind, its owner (the schema), its name — TABLE.COLUMN " +
            "for a column of a table or a view — and a detail: VIEW or MATERIALIZED VIEW for a view, " +
            "the data type of a column, or what kind of program it is. At most $MATCHES_PER_KIND hits per " +
            "kind come back, the first ones in alphabetical order. Use it when the exact name is " +
            "unknown, then call describe_table or get_object_source for the hit that matters.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun searchSchema(
        @McpToolParam(required = true, description = DATABASE_ENVIRONMENT)
        environment: String,
        @McpToolParam(required = true, description = "Part of a name to look for. Case does not matter.")
        term: String,
        @McpToolParam(
            required = false,
            description = "What to look for: any of TABLE, VIEW, COLUMN and PROGRAM. All four when left out.",
        )
        kinds: List<String>?,
    ): List<SchemaMatch> {
        val requestedKinds = kinds?.map(::parseKind)?.toSet() ?: SchemaObjectKind.entries.toSet()
        return catalogReader.search(
            environmentRegistry.requireEnvironment(environment),
            term,
            requestedKinds,
            MATCHES_PER_KIND,
        )
    }

    @McpTool(
        name = "get_object_source",
        description = "Returns the source code of a stored program or a view as one text rather than " +
            "one row per line. On Oracle a program is a package, package body, procedure, function, " +
            "trigger or type; on PostgreSQL a function or procedure, printed as its full CREATE " +
            "statement. A view or materialized view comes back as its defining query. " +
            "Every matching object is a separate entry, including each overload of a PostgreSQL " +
            "function. " +
            "A long object comes back one range of lines at a time. Each entry carries totalLines " +
            "for the whole object, the fromLine and toLine actually returned, and hasMore. While " +
            "hasMore is true the object continues: call again with fromLine set to toLine + 1. " +
            "Large packages can run to tens of thousands of lines, so read the range you need " +
            "rather than the whole body. An object that does not exist yields an empty answer.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun getObjectSource(
        @McpToolParam(required = true, description = DATABASE_ENVIRONMENT)
        environment: String,
        @McpToolParam(required = true, description = "Object name, without a schema prefix. Case does not matter.")
        objectName: String,
        @McpToolParam(
            required = false,
            description = "Restricts the answer to one kind of object. On Oracle for example PACKAGE " +
                "or PACKAGE BODY; left out, a package returns both its specification and its body. " +
                "On PostgreSQL FUNCTION or PROCEDURE. On both VIEW or MATERIALIZED VIEW.",
        )
        objectType: String?,
        @McpToolParam(
            required = false,
            description = "First line of the range, counting from 1. Reading starts at the beginning when left out.",
        )
        fromLine: Int?,
        @McpToolParam(
            required = false,
            description = "How many lines to return. The server caps this at its own limit, and " +
                "toLine in the answer says where the returned range really ended.",
        )
        maxLines: Int?,
    ): List<ObjectSource> {
        val databaseEnvironment = environmentRegistry.requireEnvironment(environment)
        val lineLimit = databaseEnvironment.settings.maxSourceLines
        return catalogReader.readSource(
            environment = databaseEnvironment,
            objectName = objectName,
            objectType = objectType,
            fromLine = fromLine ?: 1,
            maxLines = maxLines?.coerceIn(1, lineLimit) ?: lineLimit,
        )
    }

    @McpTool(
        name = "get_query_plan",
        description = "Returns the execution plan of a query as text. The two databases get it " +
            "differently, and that changes what sql means. " +
            "PostgreSQL: sql is the whole statement, planned with EXPLAIN without being run. " +
            "Oracle: EXPLAIN PLAN writes into PLAN_TABLE, which a read-only connection cannot do, " +
            "so the plan is read from the cursor cache instead. There sql is a distinctive fragment " +
            "of a statement that has already run, found within the first 1000 characters of its " +
            "text; run the query with run_sql_query first, then ask for its plan. Reading the " +
            "cursor cache needs SELECT_CATALOG_ROLE or an equivalent grant on V\$SQL. On Oracle the " +
            "answer also names the cursor it came from by sql_id and child number.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun getQueryPlan(
        @McpToolParam(required = true, description = DATABASE_ENVIRONMENT)
        environment: String,
        @McpToolParam(
            required = true,
            description = "PostgreSQL: the statement to plan. Oracle: a distinctive fragment of a " +
                "statement that has already run.",
        )
        sql: String,
    ): QueryPlan {
        val databaseEnvironment = environmentRegistry.requireEnvironment(environment)
        return catalogReader.readPlan(databaseEnvironment, sql)
            ?: throw IllegalArgumentException(
                "No statement matching '$sql' is in the cursor cache of ${databaseEnvironment.name}. " +
                    "Run it first, or use a different fragment.",
            )
    }

    private fun parseKind(kind: String): SchemaObjectKind =
        SchemaObjectKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown kind '$kind'. Use TABLE, VIEW, COLUMN or PROGRAM.")

    private companion object {
        const val MATCHES_PER_KIND = 100
    }
}
