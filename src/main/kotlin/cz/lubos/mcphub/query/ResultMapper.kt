package cz.lubos.mcphub.query

import cz.lubos.mcphub.config.EnvironmentSettings
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
            // The PostgreSQL driver reports timestamptz as TIMESTAMP too, but refuses to drop its offset.
            Types.TIMESTAMP -> if (metaData.getColumnTypeName(columnIndex).equals("timestamptz", ignoreCase = true)) {
                resultSet.getObject(columnIndex, OffsetDateTime::class.java)?.let(::formatOffsetDateTime)
            } else {
                resultSet.getObject(columnIndex, LocalDateTime::class.java)?.let(::formatLocalDateTime)
            }

            // Only a PostgreSQL date arrives as DATE: it has no time of day, and the Oracle driver
            // reports its own DATE, which does, as TIMESTAMP.
            Types.DATE ->
                resultSet.getObject(columnIndex, LocalDate::class.java)?.let(::formatLocalDate)

            Types.TIMESTAMP_WITH_TIMEZONE, ORACLE_TIMESTAMP_WITH_TIME_ZONE ->
                resultSet.getObject(columnIndex, OffsetDateTime::class.java)?.let(::formatOffsetDateTime)

            // The session shows it in the JVM's zone, which differs between machines; UTC reads the same everywhere.
            ORACLE_TIMESTAMP_WITH_LOCAL_TIME_ZONE ->
                resultSet.getTimestamp(columnIndex)?.toInstant()?.atOffset(ZoneOffset.UTC)?.let(::formatOffsetDateTime)

            ORACLE_INTERVAL_DAY_TO_SECOND -> resultSet.getString(columnIndex)?.let { text ->
                formatIntervalDayToSecond(text, metaData.getPrecision(columnIndex), metaData.getScale(columnIndex))
            }

            ORACLE_INTERVAL_YEAR_TO_MONTH -> resultSet.getString(columnIndex)?.let { text ->
                formatIntervalYearToMonth(text, metaData.getPrecision(columnIndex))
            }

            // A PostgreSQL numeric may also be NaN or infinite, which BigDecimal cannot hold.
            Types.NUMERIC, Types.DECIMAL -> resultSet.getString(columnIndex)?.let { text ->
                if (text in NON_FINITE_NUMBERS) text else BigDecimal(text).toPlainString()
            }

            // PostgreSQL reports its boolean as BIT, and the Oracle driver reads a null BOOLEAN as "false".
            Types.BOOLEAN, Types.BIT -> if (metaData.getColumnTypeName(columnIndex).lowercase() in BOOLEAN_TYPE_NAMES) {
                resultSet.getBoolean(columnIndex).takeUnless { resultSet.wasNull() }?.toString()
            } else {
                resultSet.getString(columnIndex)
            }

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

    // The PostgreSQL driver turns infinity into the largest value Java can hold. toString would
    // drop the seconds at a full minute; the ISO formatter always writes them.
    private fun formatLocalDateTime(value: LocalDateTime): String = when (value) {
        LocalDateTime.MAX -> "infinity"
        LocalDateTime.MIN -> "-infinity"
        else -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value)
    }

    private fun formatOffsetDateTime(value: OffsetDateTime): String = when (value) {
        OffsetDateTime.MAX -> "infinity"
        OffsetDateTime.MIN -> "-infinity"
        else -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value)
    }

    private fun formatLocalDate(value: LocalDate): String = when (value) {
        LocalDate.MAX -> "infinity"
        LocalDate.MIN -> "-infinity"
        else -> value.toString()
    }

    /** Written the way Oracle itself writes it, `+01 02:03:04.500000`, rather than the driver's `1 2:3:4.5`. */
    private fun formatIntervalDayToSecond(text: String, dayPrecision: Int, secondPrecision: Int): String {
        val match = INTERVAL_DAY_TO_SECOND.matchEntire(text) ?: return text
        val (sign, days, hours, minutes, seconds, fraction) = match.destructured
        val fractionText = if (secondPrecision > 0) "." + fraction.padEnd(secondPrecision, '0') else ""
        return "${intervalSign(sign)}${days.padStart(dayPrecision, '0')} " +
            "${hours.padStart(2, '0')}:${minutes.padStart(2, '0')}:${seconds.padStart(2, '0')}$fractionText"
    }

    private fun formatIntervalYearToMonth(text: String, yearPrecision: Int): String {
        val match = INTERVAL_YEAR_TO_MONTH.matchEntire(text) ?: return text
        val (sign, years, months) = match.destructured
        return "${intervalSign(sign)}${years.padStart(yearPrecision, '0')}-${months.padStart(2, '0')}"
    }

    private fun intervalSign(sign: String) = if (sign == "-") "-" else "+"

    private companion object {
        // Oracle's own type codes, outside java.sql.Types; the driver is only on the runtime classpath.
        const val ORACLE_TIMESTAMP_WITH_TIME_ZONE = -101
        const val ORACLE_TIMESTAMP_WITH_LOCAL_TIME_ZONE = -102
        const val ORACLE_INTERVAL_YEAR_TO_MONTH = -103
        const val ORACLE_INTERVAL_DAY_TO_SECOND = -104

        val NON_FINITE_NUMBERS = setOf("NaN", "Infinity", "-Infinity")
        val BOOLEAN_TYPE_NAMES = setOf("bool", "boolean")
        val INTERVAL_DAY_TO_SECOND = Regex("""(-?)(\d+) (\d+):(\d+):(\d+)(?:\.(\d+))?""")
        val INTERVAL_YEAR_TO_MONTH = Regex("""(-?)(\d+)-(\d+)""")
    }
}
