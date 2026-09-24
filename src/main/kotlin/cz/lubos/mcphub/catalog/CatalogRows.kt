package cz.lubos.mcphub.catalog

import cz.lubos.mcphub.database.DatabaseEnvironment
import java.sql.Connection
import java.sql.ResultSet

/** Row plumbing shared by every dialect; only the SQL above it differs. */
internal fun forEachRow(
    connection: Connection,
    environment: DatabaseEnvironment,
    sql: String,
    parameters: List<Any?>,
    consume: (ResultSet) -> Unit,
) {
    connection.prepareStatement(sql).use { statement ->
        statement.queryTimeout = environment.settings.queryTimeoutSeconds
        parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
        statement.executeQuery().use { resultSet ->
            while (resultSet.next()) {
                consume(resultSet)
            }
        }
    }
}

internal fun <T> collectRows(
    connection: Connection,
    environment: DatabaseEnvironment,
    sql: String,
    parameters: List<Any?>,
    map: (ResultSet) -> T,
): List<T> = buildList {
    forEachRow(connection, environment, sql, parameters) { row -> add(map(row)) }
}

internal fun placeholders(values: List<String>) = values.joinToString(", ") { "?" }

internal data class TableIdentity(val owner: String, val table: String)

/** Splits a whole routine body into the requested range of lines. */
internal fun sliceLines(source: String, fromLine: Int, maxLines: Int): Triple<String, Int, Int> {
    val lines = source.lines()
    val firstIndex = (fromLine - 1).coerceIn(0, maxOf(lines.size - 1, 0))
    val lastIndex = minOf(firstIndex + maxLines, lines.size)
    val slice = lines.subList(firstIndex, lastIndex).joinToString("\n")
    return Triple(slice, lines.size, lastIndex)
}
