package cz.lubos.mcphub.status

import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.database.DatabaseEnvironment
import cz.lubos.mcphub.database.PoolUsage
import cz.lubos.mcphub.entra.EntraAccount
import cz.lubos.mcphub.entra.EntraAccountRegistry
import cz.lubos.mcphub.entra.EntraAccountState
import cz.lubos.mcphub.entra.PgpassExport
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
    /** Present when the environment signs in with Microsoft Entra ID. */
    val entra: EntraAccountView?,
)

data class EntraAccountView(
    val account: String,
    val state: EntraAccountState,
    val user: String?,
    val signedInSince: String?,
    val accessTokenValidUntil: String?,
    val lastRefresh: String?,
    val lastError: String?,
    /** Present when the account writes its token to a PostgreSQL password file. */
    val passwordFile: PasswordFileView?,
)

data class PasswordFileView(
    val path: String,
    val lastWritten: String?,
    val lastError: String?,
)

/** One description of what the hub is connected to, shared by the MCP tool and the status page. */
@Component
class EnvironmentStatusReporter(
    private val registries: List<TargetRegistry>,
    private val connectionProbe: ConnectionProbe,
    private val entraAccountRegistry: EntraAccountRegistry,
    private val pgpassExport: PgpassExport,
) {

    fun report(): List<EnvironmentView> = registries.flatMap(TargetRegistry::targets).map(::describe)

    fun entraAccounts(): List<EntraAccountView> = entraAccountRegistry.all().map(::describe)

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
            entra = (target as? DatabaseEnvironment)?.entraAccount?.let(::describe),
        )
    }

    private fun describe(account: EntraAccount): EntraAccountView {
        val status = account.status()
        return EntraAccountView(
            account = account.name,
            state = status.state,
            user = status.user,
            signedInSince = status.signedInSince?.toString(),
            accessTokenValidUntil = status.accessTokenValidUntil?.toString(),
            lastRefresh = status.lastRefresh?.toString(),
            lastError = status.lastError,
            passwordFile = pgpassExport.status(account.name)?.let { file ->
                PasswordFileView(file.path, file.lastWritten?.toString(), file.lastError)
            },
        )
    }
}
