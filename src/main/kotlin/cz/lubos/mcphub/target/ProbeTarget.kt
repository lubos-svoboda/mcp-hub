package cz.lubos.mcphub.target

import cz.lubos.mcphub.config.EnvironmentType

/**
 * Anything the hub connects to and reports the state of. A database reaches this through a
 * pooled JDBC connection, Grafana over HTTP; the probe above them knows neither.
 */
interface ProbeTarget {
    val name: String
    val description: String
    val type: EnvironmentType
    val readOnly: Boolean

    /** Throws when the target cannot be reached. The exception carries the reason. */
    fun checkReachable()
}

/** Implemented by each source of targets, so the probe does not have to know them by name. */
interface TargetRegistry {
    fun targets(): Collection<ProbeTarget>
}
