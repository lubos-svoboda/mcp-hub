package cz.lubos.mcphub.tool

// Shared parameter descriptions, so that every tool names its environment the same way.

internal const val DATABASE_ENVIRONMENT =
    "Name of a database environment, exactly as list_environments reports it (type ORACLE or POSTGRESQL)."

internal const val GRAFANA_ENVIRONMENT =
    "Name of a Grafana instance, exactly as list_environments reports it (type GRAFANA)."

internal const val JQ_EXPRESSION =
    "Optional jq expression applied to the JSON answer before it is returned, so that only the " +
        "part needed comes back."
