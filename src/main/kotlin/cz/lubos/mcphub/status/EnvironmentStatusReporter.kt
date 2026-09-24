package cz.lubos.mcphub.status

import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.database.DatabaseEnvironment
import cz.lubos.mcphub.database.PoolUsage
import cz.lubos.mcphub.target.ConnectionProbe
import cz.lubos.mcphub.target.ConnectionState
import cz.lubos.mcphub.target.ProbeTarget
import cz.lubos.mcphub.target.TargetRegistry
import org.springframework.stereotype.Component

data class EnvironmentView(
    val name: String,
    val description: String,
    val type: EnvironmentType,
    val state: ConnectionState,
    val since: String,
    val lastError: String?,
    val readOnly: Boolean,
    val pool: PoolUsage?,
)

/** One description of what the hub is connected to, shared by the MCP tool and the status page. */
@Component
class EnvironmentStatusReporter(
    private val registries: List<TargetRegistry>,
    private val connectionProbe: ConnectionProbe,
) {

    fun report(): List<EnvironmentView> = registries.flatMap(TargetRegistry::targets).map(::describe)

    private fun describe(target: ProbeTarget): EnvironmentView {
        val status = checkNotNull(connectionProbe.statusOf(target.name)) {
            "No connection status was recorded for ${target.name}"
        }
        return EnvironmentView(
            name = target.name,
            description = target.description,
            type = target.type,
            state = status.connectionState,
            since = status.since.toString(),
            lastError = status.lastError,
            readOnly = target.readOnly,
            // Only a pooled target has a pool; everything else reports none rather than zeros.
            pool = when (target) {
                is DatabaseEnvironment -> target.poolUsage()
                else -> null
            },
        )
    }
}
