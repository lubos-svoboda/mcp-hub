package cz.lubos.mcphub.query

import cz.lubos.mcphub.config.EnvironmentSettings

/** What one query may return; [cappedRequests] names what the call asked beyond the environment's ceiling. */
data class QueryLimits(
    val rows: Int,
    val textValueCharacters: Int,
    val cappedRequests: List<String> = emptyList(),
) {
    companion object {
        fun defaultsOf(settings: EnvironmentSettings) =
            QueryLimits(settings.defaultRows, settings.defaultTextValueCharacters)

        /** A missing request takes the environment's default; text characters of 0 ask for the most allowed. */
        fun requested(settings: EnvironmentSettings, rows: Int?, textValueCharacters: Int?): QueryLimits {
            require(rows == null || rows > 0) { "rows must be at least 1." }
            require(textValueCharacters == null || textValueCharacters >= 0) { "textValueCharacters must not be negative." }
            val cappedRequests = buildList {
                if (rows != null && rows > settings.maxRows) {
                    add("rows was lowered from $rows to the ceiling of ${settings.maxRows}.")
                }
                if (textValueCharacters != null && textValueCharacters > settings.maxTextValueCharacters) {
                    add(
                        "textValueCharacters was lowered from $textValueCharacters to the ceiling of " +
                            "${settings.maxTextValueCharacters}.",
                    )
                }
            }
            return QueryLimits(
                rows = minOf(rows ?: settings.defaultRows, settings.maxRows),
                textValueCharacters = when (textValueCharacters) {
                    null -> settings.defaultTextValueCharacters
                    0 -> settings.maxTextValueCharacters
                    else -> minOf(textValueCharacters, settings.maxTextValueCharacters)
                },
                cappedRequests = cappedRequests,
            )
        }
    }
}
