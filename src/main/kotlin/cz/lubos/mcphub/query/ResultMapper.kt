package cz.lubos.mcphub.query

import cz.lubos.mcphub.config.EnvironmentSettings
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * Columns are listed once and rows hold bare values, which costs far fewer tokens than
 * repeating every column name on every row.
 *
 * Every value is rendered as text: a number kept as a JSON number would silently lose
 * precision once it no longer fits a double.
 */
data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val truncatedAtRowLimit: Boolean,
    val truncatedAtResponseSize: Boolean,
    val truncatedTextValues: Boolean,
)

@Component
class ResultMapper {

    fun map(resultSet: ResultSet, settings: EnvironmentSettings): QueryResult {
        val metaData = resultSet.metaData
        val columnIndexes = 1..metaData.columnCount
        val columns = columnIndexes.map(metaData::getColumnLabel)

        val rows = mutableListOf<List<String?>>()
        var renderedCharacters = 0
        var truncatedTextValues = false
        var truncatedAtResponseSize = false

        while (rows.size < settings.maxRows && resultSet.next()) {
            val row = columnIndexes.map { columnIndex ->
                val value = readValue(resultSet, metaData, columnIndex, settings.maxTextValueCharacters)
                if (value != null && value.length > settings.maxTextValueCharacters) {
                    truncatedTextValues = true
                    value.take(settings.maxTextValueCharacters)
                } else {
                    value
                }
            }
            rows += row
            renderedCharacters += row.sumOf { it?.length ?: 0 }
            if (renderedCharacters >= settings.maxResponseCharacters) {
                truncatedAtResponseSize = true
                break
            }
        }

        // The statement was allowed one row beyond the limit purely so that one more
        // available row proves the result is incomplete.
        val truncatedAtRowLimit =
            !truncatedAtResponseSize && rows.size == settings.maxRows && resultSet.next()

        return QueryResult(
            columns = columns,
            rows = rows,
            truncatedAtRowLimit = truncatedAtRowLimit,
            truncatedAtResponseSize = truncatedAtResponseSize,
            truncatedTextValues = truncatedTextValues,
        )
    }

    private fun readValue(
        resultSet: ResultSet,
        metaData: ResultSetMetaData,
        columnIndex: Int,
        maxTextValueCharacters: Int,
    ): String? =
        when (metaData.getColumnType(columnIndex)) {
            // Read as a local date-time rather than through java.sql.Timestamp: Oracle DATE and
            // TIMESTAMP hold no zone, and converting via an instant would shift them by whatever
            // the JVM default zone happens to be.
            Types.DATE, Types.TIMESTAMP ->
                resultSet.getObject(columnIndex, LocalDateTime::class.java)?.toString()

            Types.TIMESTAMP_WITH_TIMEZONE ->
                resultSet.getObject(columnIndex, OffsetDateTime::class.java)?.toString()

            Types.NUMERIC, Types.DECIMAL ->
                resultSet.getBigDecimal(columnIndex)?.toPlainString()

            Types.CLOB, Types.NCLOB ->
                readClob(resultSet, columnIndex, maxTextValueCharacters)

            Types.BLOB ->
                readBlobDescription(resultSet, columnIndex)

            // PostgreSQL bytea and Oracle RAW are plain values, not locators: getBlob refuses them.
            Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY ->
                resultSet.getBytes(columnIndex)?.let { bytes -> describeBinary(bytes.size.toLong()) }

            else -> resultSet.getString(columnIndex)
        }

    /** Only the cap plus one character is fetched, so a huge value never reaches memory. */
    private fun readClob(resultSet: ResultSet, columnIndex: Int, maxTextValueCharacters: Int): String? {
        val clob = resultSet.getClob(columnIndex) ?: return null
        try {
            return clob.getSubString(1, maxTextValueCharacters + 1)
        } finally {
            clob.free()
        }
    }

    private fun readBlobDescription(resultSet: ResultSet, columnIndex: Int): String? {
        val blob = resultSet.getBlob(columnIndex) ?: return null
        try {
            return describeBinary(blob.length())
        } finally {
            blob.free()
        }
    }

    private fun describeBinary(length: Long) = "<binary, $length bytes>"
}
