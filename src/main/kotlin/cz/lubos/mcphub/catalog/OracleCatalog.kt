package cz.lubos.mcphub.catalog

import cz.lubos.mcphub.database.DatabaseEnvironment
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.ResultSet

@Component
class OracleCatalog : DialectCatalog {

    override fun describeTables(
        connection: Connection,
        environment: DatabaseEnvironment,
        tableNames: List<String>,
    ): List<TableDescription> {
        val names = tableNames.map(String::uppercase)
        val columnsByTable = readColumns(connection, environment, names)
        val constraintsByTable = readConstraints(connection, environment, names)
        val indexesByTable = readIndexes(connection, environment, names)

        return columnsByTable.map { (identity, columns) ->
            TableDescription(
                owner = identity.owner,
                table = identity.table,
                columns = columns,
                constraints = constraintsByTable[identity].orEmpty(),
                indexes = indexesByTable[identity].orEmpty(),
            )
        }
    }

    override fun search(
        connection: Connection,
        environment: DatabaseEnvironment,
        term: String,
        kinds: Set<SchemaObjectKind>,
        limitPerKind: Int,
    ): List<SchemaMatch> {
        val pattern = "%${term.uppercase()}%"
        return buildList {
            if (SchemaObjectKind.TABLE in kinds) {
                addAll(
                    collectRows(
                        connection,
                        environment,
                        firstRows(
                            "select owner, table_name from all_tables where upper(table_name) like ?" +
                                ownerClause(environment, "owner") + " order by table_name",
                            limitPerKind,
                        ),
                        listOf(pattern) + ownerParameters(environment),
                    ) { row ->
                        SchemaMatch(
                            SchemaObjectKind.TABLE,
                            row.getString("owner"),
                            row.getString("table_name"),
                            "TABLE",
                        )
                    },
                )
            }
            if (SchemaObjectKind.COLUMN in kinds) {
                addAll(
                    collectRows(
                        connection,
                        environment,
                        firstRows(
                            "select owner, table_name, column_name, $TYPE_COLUMNS from all_tab_columns " +
                                "where upper(column_name) like ?" + ownerClause(environment, "owner") +
                                " order by table_name, column_name",
                            limitPerKind,
                        ),
                        listOf(pattern) + ownerParameters(environment),
                    ) { row ->
                        SchemaMatch(
                            kind = SchemaObjectKind.COLUMN,
                            owner = row.getString("owner"),
                            name = "${row.getString("table_name")}.${row.getString("column_name")}",
                            detail = renderType(row),
                        )
                    },
                )
            }
            if (SchemaObjectKind.PROGRAM in kinds) {
                addAll(
                    collectRows(
                        connection,
                        environment,
                        firstRows(
                            "select owner, object_name, object_type from all_objects where object_type in " +
                                "('PACKAGE', 'PACKAGE BODY', 'PROCEDURE', 'FUNCTION', 'TRIGGER', 'TYPE', 'TYPE BODY') " +
                                "and upper(object_name) like ?" + ownerClause(environment, "owner") +
                                " order by object_name, object_type",
                            limitPerKind,
                        ),
                        listOf(pattern) + ownerParameters(environment),
                    ) { row ->
                        SchemaMatch(
                            kind = SchemaObjectKind.PROGRAM,
                            owner = row.getString("owner"),
                            name = row.getString("object_name"),
                            detail = row.getString("object_type"),
                        )
                    },
                )
            }
        }
    }

    /** ALL_SOURCE is stored one row per line, so a range of lines is a plain WHERE clause. */
    override fun readSource(
        connection: Connection,
        environment: DatabaseEnvironment,
        objectName: String,
        objectType: String?,
        fromLine: Int,
        maxLines: Int,
    ): List<ObjectSource> {
        val firstLine = maxOf(fromLine, 1)
        val sql = buildString {
            append("select owner, name, type, line, text, total_lines from (")
            append("select owner, name, type, line, text, ")
            append("count(*) over (partition by owner, name, type) as total_lines ")
            append("from all_source where name = ?")
            if (objectType != null) append(" and type = ?")
            append(ownerClause(environment, "owner"))
            append(") where line between ? and ? order by owner, type, line")
        }
        val parameters = buildList {
            add(objectName.uppercase())
            if (objectType != null) add(objectType.uppercase())
            addAll(ownerParameters(environment))
            add(firstLine)
            add(firstLine + maxLines - 1)
        }

        val slices = linkedMapOf<Triple<String, String, String>, SourceSlice>()
        forEachRow(connection, environment, sql, parameters) { row ->
            val key = Triple(row.getString("owner"), row.getString("name"), row.getString("type"))
            val slice = slices.getOrPut(key) { SourceSlice(row.getInt("total_lines")) }
            slice.text.append(row.getString("text"))
            slice.lastLine = row.getInt("line")
        }

        return slices.map { (key, slice) ->
            ObjectSource(
                owner = key.first,
                name = key.second,
                type = key.third,
                totalLines = slice.totalLines,
                fromLine = firstLine,
                toLine = slice.lastLine,
                hasMore = slice.lastLine < slice.totalLines,
                source = slice.text.toString(),
            )
        }
    }

    /**
     * EXPLAIN PLAN writes into PLAN_TABLE and every statement here runs read-only, so Oracle
     * refuses it with ORA-01456. The plan therefore comes from a statement that already ran.
     */
    override fun readPlan(
        connection: Connection,
        environment: DatabaseEnvironment,
        sql: String,
    ): QueryPlan? {
        val cursors = collectRows(
            connection,
            environment,
            """
            select sql_id, child_number, sql_text from (
              select sql_id, child_number, sql_text
              from v${'$'}sql
              where upper(sql_text) like ?
              order by last_active_time desc
            ) where rownum = 1
            """.trimIndent(),
            listOf("%${sql.uppercase()}%"),
        ) { row ->
            QueryPlan(
                sqlId = row.getString("sql_id"),
                childNumber = row.getInt("child_number"),
                sqlText = row.getString("sql_text"),
                plan = "",
            )
        }

        val cursor = cursors.firstOrNull() ?: return null
        val plan = StringBuilder()
        forEachRow(
            connection,
            environment,
            "select plan_table_output from table(dbms_xplan.display_cursor(?, ?, 'ALLSTATS LAST'))",
            listOf(cursor.sqlId, cursor.childNumber),
        ) { row ->
            plan.appendLine(row.getString(1))
        }
        return cursor.copy(plan = plan.toString())
    }

    private fun readColumns(
        connection: Connection,
        environment: DatabaseEnvironment,
        tableNames: List<String>,
    ): Map<TableIdentity, List<ColumnDescription>> {
        val columns = linkedMapOf<TableIdentity, MutableList<ColumnDescription>>()
        forEachRow(
            connection,
            environment,
            "select owner, table_name, column_name, $TYPE_COLUMNS, nullable from all_tab_columns " +
                "where table_name in (${placeholders(tableNames)})" +
                ownerClause(environment, "owner") + " order by owner, table_name, column_id",
            tableNames + ownerParameters(environment),
        ) { row ->
            val identity = TableIdentity(row.getString("owner"), row.getString("table_name"))
            columns.getOrPut(identity) { mutableListOf() } += ColumnDescription(
                name = row.getString("column_name"),
                type = renderType(row),
                nullable = row.getString("nullable") == "Y",
            )
        }
        return columns
    }

    private fun readConstraints(
        connection: Connection,
        environment: DatabaseEnvironment,
        tableNames: List<String>,
    ): Map<TableIdentity, List<ConstraintDescription>> {
        val grouped = linkedMapOf<Pair<TableIdentity, String>, ConstraintDescription>()
        forEachRow(
            connection,
            environment,
            """
            select c.owner, c.table_name, c.constraint_name, c.constraint_type,
                   referenced.table_name as referenced_table, columns.column_name
            from all_constraints c
            left join all_cons_columns columns
              on columns.owner = c.owner and columns.constraint_name = c.constraint_name
            left join all_constraints referenced
              on referenced.owner = c.r_owner and referenced.constraint_name = c.r_constraint_name
            where c.table_name in (${placeholders(tableNames)})
              and not (c.constraint_type = 'C' and c.generated = 'GENERATED NAME')
            """.trimIndent() + ownerClause(environment, "c.owner") +
                " order by c.constraint_name, columns.position",
            tableNames + ownerParameters(environment),
        ) { row ->
            val identity = TableIdentity(row.getString("owner"), row.getString("table_name"))
            val constraintName = row.getString("constraint_name")
            val existing = grouped[identity to constraintName]
            grouped[identity to constraintName] = ConstraintDescription(
                name = constraintName,
                type = constraintTypeName(row.getString("constraint_type")),
                columns = existing?.columns.orEmpty() + listOfNotNull(row.getString("column_name")),
                referencedTable = row.getString("referenced_table"),
            )
        }
        return grouped.entries.groupBy({ it.key.first }, { it.value })
    }

    private fun readIndexes(
        connection: Connection,
        environment: DatabaseEnvironment,
        tableNames: List<String>,
    ): Map<TableIdentity, List<IndexDescription>> {
        val grouped = linkedMapOf<Pair<TableIdentity, String>, IndexDescription>()
        forEachRow(
            connection,
            environment,
            """
            select i.table_owner, i.table_name, i.index_name, i.uniqueness, ic.column_name
            from all_indexes i
            join all_ind_columns ic on ic.index_owner = i.owner and ic.index_name = i.index_name
            where i.table_name in (${placeholders(tableNames)})
            """.trimIndent() + ownerClause(environment, "i.table_owner") +
                " order by i.index_name, ic.column_position",
            tableNames + ownerParameters(environment),
        ) { row ->
            val identity = TableIdentity(row.getString("table_owner"), row.getString("table_name"))
            val indexName = row.getString("index_name")
            val existing = grouped[identity to indexName]
            grouped[identity to indexName] = IndexDescription(
                name = indexName,
                unique = row.getString("uniqueness") == "UNIQUE",
                columns = existing?.columns.orEmpty() + row.getString("column_name"),
            )
        }
        return grouped.entries.groupBy({ it.key.first }, { it.value })
    }

    private fun renderType(row: ResultSet): String {
        val dataType = row.getString("data_type")
        val precision = row.getObject("data_precision") as? Number
        val scale = row.getObject("data_scale") as? Number
        val length = row.getObject("data_length") as? Number
        val charLength = row.getObject("char_length") as? Number
        val charUsed = row.getString("char_used")
        return when {
            dataType == "NUMBER" && precision != null && scale != null && scale.toInt() != 0 ->
                "NUMBER(${precision.toInt()},${scale.toInt()})"

            dataType == "NUMBER" && precision != null -> "NUMBER(${precision.toInt()})"

            // National types always count characters; the others follow the column's own semantics,
            // and data_length is in bytes, which misstates a column declared as VARCHAR2(100 CHAR).
            dataType in listOf("NVARCHAR2", "NCHAR") && charLength != null ->
                "$dataType(${charLength.toInt()})"

            dataType in listOf("VARCHAR2", "CHAR") && charUsed == "C" && charLength != null ->
                "$dataType(${charLength.toInt()} CHAR)"

            dataType in listOf("VARCHAR2", "CHAR", "RAW") && length != null ->
                "$dataType(${length.toInt()})"

            else -> dataType
        }
    }

    /** ROWNUM is assigned before ORDER BY, so the limit has to wrap the ordered query to keep the first rows. */
    private fun firstRows(orderedSql: String, limit: Int) = "select * from ($orderedSql) where rownum <= $limit"

    private fun constraintTypeName(code: String?) = when (code) {
        "P" -> "PRIMARY KEY"
        "U" -> "UNIQUE"
        "R" -> "FOREIGN KEY"
        "C" -> "CHECK"
        else -> code ?: "UNKNOWN"
    }

    private fun ownerClause(environment: DatabaseEnvironment, column: String) =
        if (environment.settings.schema == null) "" else " and $column = ?"

    private fun ownerParameters(environment: DatabaseEnvironment) =
        listOfNotNull(environment.settings.schema)

    private class SourceSlice(val totalLines: Int) {
        val text = StringBuilder()
        var lastLine = 0
    }

    private companion object {
        const val TYPE_COLUMNS = "data_type, data_length, data_precision, data_scale, char_length, char_used"
    }
}
