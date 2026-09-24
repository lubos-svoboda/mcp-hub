package cz.lubos.mcphub.catalog

import cz.lubos.mcphub.database.DatabaseEnvironment
import org.springframework.stereotype.Component
import java.sql.Connection

/**
 * Reads pg_catalog rather than information_schema: `format_type` renders a column exactly as it
 * reads in DDL, which information_schema cannot do without reassembling it by hand.
 */
@Component
class PostgresCatalog : DialectCatalog {

    override fun describeTables(
        connection: Connection,
        environment: DatabaseEnvironment,
        tableNames: List<String>,
    ): List<TableDescription> {
        val names = tableNames.map(String::lowercase)
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
        val pattern = "%${term.lowercase()}%"
        return buildList {
            if (SchemaObjectKind.TABLE in kinds) {
                addAll(
                    collectRows(
                        connection,
                        environment,
                        """
                        select n.nspname as schema_name, c.relname as table_name
                        from pg_class c join pg_namespace n on n.oid = c.relnamespace
                        where c.relkind in ('r', 'p', 'v', 'm') and lower(c.relname) like ?
                        """.trimIndent() + schemaClause(environment, "n.nspname") +
                            " order by c.relname limit $limitPerKind",
                        listOf(pattern) + schemaParameters(environment),
                    ) { row ->
                        SchemaMatch(
                            SchemaObjectKind.TABLE,
                            row.getString("schema_name"),
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
                        """
                        select n.nspname as schema_name, c.relname as table_name, a.attname as column_name,
                               format_type(a.atttypid, a.atttypmod) as column_type
                        from pg_attribute a
                        join pg_class c on c.oid = a.attrelid
                        join pg_namespace n on n.oid = c.relnamespace
                        where a.attnum > 0 and not a.attisdropped
                          and c.relkind in ('r', 'p', 'v', 'm') and lower(a.attname) like ?
                        """.trimIndent() + schemaClause(environment, "n.nspname") +
                            " order by c.relname, a.attname limit $limitPerKind",
                        listOf(pattern) + schemaParameters(environment),
                    ) { row ->
                        SchemaMatch(
                            kind = SchemaObjectKind.COLUMN,
                            owner = row.getString("schema_name"),
                            name = "${row.getString("table_name")}.${row.getString("column_name")}",
                            detail = row.getString("column_type"),
                        )
                    },
                )
            }
            if (SchemaObjectKind.PROGRAM in kinds) {
                addAll(
                    collectRows(
                        connection,
                        environment,
                        """
                        select n.nspname as schema_name, p.proname as routine_name, p.prokind
                        from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                        where p.prokind in ('f', 'p') and lower(p.proname) like ?
                        """.trimIndent() + schemaClause(environment, "n.nspname") +
                            " order by p.proname limit $limitPerKind",
                        listOf(pattern) + schemaParameters(environment),
                    ) { row ->
                        SchemaMatch(
                            kind = SchemaObjectKind.PROGRAM,
                            owner = row.getString("schema_name"),
                            name = row.getString("routine_name"),
                            detail = routineKind(row.getString("prokind")),
                        )
                    },
                )
            }
        }
    }

    /**
     * PostgreSQL keeps a routine body as one text, not as numbered lines the way Oracle does, so
     * the range has to be cut here instead of in the query.
     */
    override fun readSource(
        connection: Connection,
        environment: DatabaseEnvironment,
        objectName: String,
        objectType: String?,
        fromLine: Int,
        maxLines: Int,
    ): List<ObjectSource> {
        val firstLine = maxOf(fromLine, 1)
        val definitions = collectRows(
            connection,
            environment,
            """
            select n.nspname as schema_name, p.proname as routine_name, p.prokind,
                   pg_get_functiondef(p.oid) as definition
            from pg_proc p join pg_namespace n on n.oid = p.pronamespace
            where p.prokind in ('f', 'p') and lower(p.proname) = ?
            """.trimIndent() + schemaClause(environment, "n.nspname") +
                " order by n.nspname, p.proname",
            listOf(objectName.lowercase()) + schemaParameters(environment),
        ) { row ->
            Definition(
                schema = row.getString("schema_name"),
                name = row.getString("routine_name"),
                kind = routineKind(row.getString("prokind")),
                body = row.getString("definition"),
            )
        }

        return definitions
            .filter { objectType == null || it.kind.equals(objectType, ignoreCase = true) }
            .map { definition ->
                val (slice, totalLines, lastLine) = sliceLines(definition.body, firstLine, maxLines)
                ObjectSource(
                    owner = definition.schema,
                    name = definition.name,
                    type = definition.kind,
                    totalLines = totalLines,
                    fromLine = firstLine,
                    toLine = lastLine,
                    hasMore = lastLine < totalLines,
                    source = slice,
                )
            }
    }

    /**
     * Plain EXPLAIN only plans, it does not run the statement, so it works inside the read-only
     * transaction. ANALYZE would execute and is deliberately not offered.
     */
    override fun readPlan(
        connection: Connection,
        environment: DatabaseEnvironment,
        sql: String,
    ): QueryPlan {
        val plan = StringBuilder()
        forEachRow(connection, environment, "explain $sql", emptyList()) { row ->
            plan.appendLine(row.getString(1))
        }
        return QueryPlan(sqlId = null, childNumber = null, sqlText = sql, plan = plan.toString())
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
            """
            select n.nspname as schema_name, c.relname as table_name, a.attname as column_name,
                   format_type(a.atttypid, a.atttypmod) as column_type, a.attnotnull
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
            where a.attnum > 0 and not a.attisdropped and lower(c.relname) in (${placeholders(tableNames)})
            """.trimIndent() + schemaClause(environment, "n.nspname") +
                " order by n.nspname, c.relname, a.attnum",
            tableNames + schemaParameters(environment),
        ) { row ->
            val identity = TableIdentity(row.getString("schema_name"), row.getString("table_name"))
            columns.getOrPut(identity) { mutableListOf() } += ColumnDescription(
                name = row.getString("column_name"),
                type = row.getString("column_type"),
                nullable = !row.getBoolean("attnotnull"),
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
            select n.nspname as schema_name, c.relname as table_name, con.conname as constraint_name,
                   con.contype, referenced.relname as referenced_table, a.attname as column_name
            from pg_constraint con
            join pg_class c on c.oid = con.conrelid
            join pg_namespace n on n.oid = c.relnamespace
            left join pg_class referenced on referenced.oid = con.confrelid
            left join lateral unnest(con.conkey) with ordinality as key_column(attnum, ord) on true
            left join pg_attribute a on a.attrelid = c.oid and a.attnum = key_column.attnum
            where lower(c.relname) in (${placeholders(tableNames)})
            """.trimIndent() + schemaClause(environment, "n.nspname") +
                " order by con.conname, key_column.ord",
            tableNames + schemaParameters(environment),
        ) { row ->
            val identity = TableIdentity(row.getString("schema_name"), row.getString("table_name"))
            val constraintName = row.getString("constraint_name")
            val existing = grouped[identity to constraintName]
            grouped[identity to constraintName] = ConstraintDescription(
                name = constraintName,
                type = constraintTypeName(row.getString("contype")),
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
            select n.nspname as schema_name, t.relname as table_name, i.relname as index_name,
                   ix.indisunique, a.attname as column_name
            from pg_index ix
            join pg_class i on i.oid = ix.indexrelid
            join pg_class t on t.oid = ix.indrelid
            join pg_namespace n on n.oid = t.relnamespace
            left join lateral unnest(ix.indkey) with ordinality as key_column(attnum, ord) on true
            left join pg_attribute a on a.attrelid = t.oid and a.attnum = key_column.attnum
            where lower(t.relname) in (${placeholders(tableNames)})
            """.trimIndent() + schemaClause(environment, "n.nspname") +
                " order by i.relname, key_column.ord",
            tableNames + schemaParameters(environment),
        ) { row ->
            val identity = TableIdentity(row.getString("schema_name"), row.getString("table_name"))
            val indexName = row.getString("index_name")
            val existing = grouped[identity to indexName]
            grouped[identity to indexName] = IndexDescription(
                name = indexName,
                unique = row.getBoolean("indisunique"),
                columns = existing?.columns.orEmpty() + listOfNotNull(row.getString("column_name")),
            )
        }
        return grouped.entries.groupBy({ it.key.first }, { it.value })
    }

    private fun constraintTypeName(code: String?) = when (code) {
        "p" -> "PRIMARY KEY"
        "u" -> "UNIQUE"
        "f" -> "FOREIGN KEY"
        "c" -> "CHECK"
        "x" -> "EXCLUDE"
        else -> code ?: "UNKNOWN"
    }

    private fun routineKind(code: String?) = if (code == "p") "PROCEDURE" else "FUNCTION"

    /**
     * Without a configured schema the system catalogs would answer every search, so they are
     * excluded. That is a filter, not a default: a schema named in the configuration still wins.
     */
    private fun schemaClause(environment: DatabaseEnvironment, column: String) =
        if (environment.settings.schema == null) {
            " and $column not in ('pg_catalog', 'information_schema', 'pg_toast')"
        } else {
            " and $column = ?"
        }

    private fun schemaParameters(environment: DatabaseEnvironment) =
        listOfNotNull(environment.settings.schema)

    private data class Definition(
        val schema: String,
        val name: String,
        val kind: String,
        val body: String,
    )
}
