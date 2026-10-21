package cz.lubos.mcphub.tool

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Writes a query answer in TOON (Token-Oriented Object Notation): the column names once in the
 * header of the rows, and every row as one line of comma-separated values. A value that could be
 * misread is written as a JSON string, so every value reads back exactly.
 */
internal object ToonEncoder {

    private val objectMapper = ObjectMapper()

    fun encode(response: QueryResponse): String = buildString {
        appendLine("environment: ${value(response.environment)}")
        appendLine("readOnly: ${response.readOnly}")
        appendLine("rowCount: ${response.rowCount}")
        appendLine("notes[${response.notes.size}]:${response.notes.joinToString(",", prefix = " ") { value(it) }.trimEnd()}")
        append("rows[${response.rows.size}]{${response.columns.joinToString(",") { value(it) }}}:")
        response.rows.forEach { row -> append("\n  ").append(row.joinToString(",") { value(it) }) }
    }

    /** null stays the literal null; text that would read as null, a boolean or a delimiter is quoted. */
    fun value(text: String?): String = when {
        text == null -> "null"
        needsQuotes(text) -> objectMapper.writeValueAsString(text)
        else -> text
    }

    private fun needsQuotes(text: String) =
        text.isEmpty() ||
            text in LITERALS ||
            text.first().isWhitespace() ||
            text.last().isWhitespace() ||
            text.any { character -> character in DELIMITERS || character.isISOControl() }

    private val LITERALS = setOf("null", "true", "false")
    private const val DELIMITERS = ",:\"\\[]{}"
}
