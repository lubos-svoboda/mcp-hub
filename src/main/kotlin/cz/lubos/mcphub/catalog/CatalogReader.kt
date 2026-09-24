package cz.lubos.mcphub.catalog

import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.database.DatabaseEnvironment
import cz.lubos.mcphub.query.ReadOnlySession
import org.springframework.stereotype.Component

/**
 * Opens the read-only transaction and hands the work to the dialect for that environment. No
 * cache: a filtered dictionary query answers in a fraction of a second even on a schema with
 * hundreds of thousands of columns, and a cache would only pay off for operations that scan the
 * whole catalog, which none of these do.
 */
@Component
class CatalogReader(
    private val readOnlySession: ReadOnlySession,
    private val oracleCatalog: OracleCatalog,
    private val postgresCatalog: PostgresCatalog,
) {

    fun describeTables(
        environment: DatabaseEnvironment,
        tableNames: List<String>,
    ): List<TableDescription> = readOnlySession.execute(environment) { connection ->
        catalogFor(environment).describeTables(connection, environment, tableNames)
    }

    fun search(
        environment: DatabaseEnvironment,
        term: String,
        kinds: Set<SchemaObjectKind>,
        limitPerKind: Int,
    ): List<SchemaMatch> = readOnlySession.execute(environment) { connection ->
        catalogFor(environment).search(connection, environment, term, kinds, limitPerKind)
    }

    fun readSource(
        environment: DatabaseEnvironment,
        objectName: String,
        objectType: String?,
        fromLine: Int,
        maxLines: Int,
    ): List<ObjectSource> = readOnlySession.execute(environment) { connection ->
        catalogFor(environment).readSource(connection, environment, objectName, objectType, fromLine, maxLines)
    }

    fun readPlan(environment: DatabaseEnvironment, sql: String): QueryPlan? =
        readOnlySession.execute(environment) { connection ->
            catalogFor(environment).readPlan(connection, environment, sql)
        }

    private fun catalogFor(environment: DatabaseEnvironment): DialectCatalog =
        when (environment.settings.type) {
            EnvironmentType.ORACLE -> oracleCatalog
            EnvironmentType.POSTGRESQL -> postgresCatalog
            // Unreachable: only database environments become a DatabaseEnvironment at all.
            EnvironmentType.GRAFANA ->
                error("${environment.name} is a Grafana instance and has no SQL catalog.")
        }
}
