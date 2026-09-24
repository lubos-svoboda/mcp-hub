package cz.lubos.mcphub.catalog

import cz.lubos.mcphub.database.DatabaseEnvironment
import java.sql.Connection

/**
 * Everything about reading a catalog that differs between databases. The connection is opened
 * and closed by the caller, always inside a read-only transaction.
 */
interface DialectCatalog {

    fun describeTables(
        connection: Connection,
        environment: DatabaseEnvironment,
        tableNames: List<String>,
    ): List<TableDescription>

    fun search(
        connection: Connection,
        environment: DatabaseEnvironment,
        term: String,
        kinds: Set<SchemaObjectKind>,
        limitPerKind: Int,
    ): List<SchemaMatch>

    fun readSource(
        connection: Connection,
        environment: DatabaseEnvironment,
        objectName: String,
        objectType: String?,
        fromLine: Int,
        maxLines: Int,
    ): List<ObjectSource>

    /**
     * What [sql] means depends on the database, and the tool description says so: Oracle cannot
     * plan a statement without writing to PLAN_TABLE, so it looks the text up among statements
     * that already ran, while PostgreSQL plans the statement given to it.
     */
    fun readPlan(
        connection: Connection,
        environment: DatabaseEnvironment,
        sql: String,
    ): QueryPlan?
}
