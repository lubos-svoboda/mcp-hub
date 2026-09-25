package cz.lubos.mcphub.target

import java.time.Instant

enum class ConnectionState {
    CONNECTING,
    UP,
    DOWN,
}

/**
 * [since] is when the current state began, not when it was last observed, so a long
 * outage keeps reporting its original start time.
 */
data class EnvironmentStatus(
    val connectionState: ConnectionState,
    val since: Instant,
    val lastError: String? = null,
    /** Set while the target is up but lacks something the tools need, for example a datasource. */
    val note: String? = null,
)
