package cz.lubos.mcphub.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("mcp-hub")
data class HubProperties(
    val probe: ProbeProperties = ProbeProperties(),
    val defaults: EnvironmentDefaults = EnvironmentDefaults(),
    val environments: Map<String, EnvironmentProperties> = emptyMap(),
    val entraAccounts: Map<String, EntraAccountProperties> = emptyMap(),
    /** Where a browser reaches the status page; messages asking for an Entra sign-in point there. */
    val statusPageUrl: String = "http://localhost:8282/status",
    /** Host names the server answers to besides localhost, 127.0.0.1 and ::1, which it always answers to. */
    val additionalAllowedHosts: List<String> = emptyList(),
    /** Asks GitHub every few hours for the latest release, so the status page can point to a newer one. */
    val updateCheck: Boolean = true,
) {
    fun resolveSettings(): Map<String, EnvironmentSettings> =
        environments.mapValues { (name, environment) -> resolveEnvironment(name, environment) }

    private fun resolveEnvironment(name: String, environment: EnvironmentProperties): EnvironmentSettings {
        val maxRows = environment.maxRows ?: defaults.maxRows
        val maxTextValueCharacters = environment.maxTextValueCharacters ?: defaults.maxTextValueCharacters
        return EnvironmentSettings(
            name = name,
            description = environment.description,
            type = environment.type,
            schema = environment.schema,
            readOnly = environment.readOnly,
            // A default above the ceiling would never apply, so a configuration that only lowers max-rows keeps working.
            defaultRows = minOf(environment.defaultRows ?: defaults.defaultRows, maxRows),
            maxRows = maxRows,
            queryTimeoutSeconds = environment.queryTimeoutSeconds ?: defaults.queryTimeoutSeconds,
            maxResponseCharacters = environment.maxResponseCharacters ?: defaults.maxResponseCharacters,
            defaultTextValueCharacters = minOf(
                environment.defaultTextValueCharacters ?: defaults.defaultTextValueCharacters,
                maxTextValueCharacters,
            ),
            maxTextValueCharacters = maxTextValueCharacters,
            maxSourceLines = environment.maxSourceLines ?: defaults.maxSourceLines,
            connectTimeoutSeconds = environment.connectTimeoutSeconds ?: defaults.connectTimeoutSeconds,
            socketReadTimeoutSeconds = environment.socketReadTimeoutSeconds ?: defaults.socketReadTimeoutSeconds,
            pool = resolvePool(environment.pool),
            entraAccount = environment.entraAccount,
        )
    }

    private fun resolvePool(pool: PoolProperties) =
        PoolSettings(
            maximumSize = pool.maximumSize ?: defaults.pool.maximumSize,
            minimumIdle = pool.minimumIdle ?: defaults.pool.minimumIdle,
            keepaliveSeconds = pool.keepaliveSeconds ?: defaults.pool.keepaliveSeconds,
            validationTimeoutSeconds = pool.validationTimeoutSeconds ?: defaults.pool.validationTimeoutSeconds,
        )
}

data class ProbeProperties(
    val intervalSeconds: Long = 15,
)

data class EnvironmentDefaults(
    /** Rows a query returns unless the call asks for another number, up to [maxRows]. */
    val defaultRows: Int = 500,
    val maxRows: Int = 5_000,
    val queryTimeoutSeconds: Int = 60,
    val maxResponseCharacters: Int = 100_000,
    /** Length a text value is shortened to unless the call asks for another, up to [maxTextValueCharacters]. */
    val defaultTextValueCharacters: Int = 200,
    val maxTextValueCharacters: Int = 4_000,
    /** Source is paged by line, the unit ALL_SOURCE itself is stored in. */
    val maxSourceLines: Int = 1_000,
    val connectTimeoutSeconds: Int = 10,
    val socketReadTimeoutSeconds: Int = 120,
    val pool: PoolDefaults = PoolDefaults(),
)

data class PoolDefaults(
    val maximumSize: Int = 5,
    val minimumIdle: Int = 1,
    val keepaliveSeconds: Long = 120,
    val validationTimeoutSeconds: Long = 5,
)

enum class EnvironmentType {
    ORACLE,
    POSTGRESQL,
    GRAFANA,
    HARBOR,
    ;

    val isDatabase: Boolean get() = this == ORACLE || this == POSTGRESQL
}

enum class AuthenticationMethod {
    PASSWORD,

    /** A Microsoft Entra ID access token instead of a password, obtained by signing in on the status page. */
    ENTRA,
}

/**
 * One identity signed in to Microsoft Entra ID. Every environment naming it shares the sign-in,
 * so several databases reached as the same person need only one.
 */
data class EntraAccountProperties(
    /** The directory the account belongs to; a sign-in into any other is refused. */
    val tenantId: String,
    /** The application signing in. The default is the public client of the Azure CLI, known to every tenant. */
    val clientId: String = AZURE_CLI_CLIENT_ID,
    /** When set, a sign-in by anybody else is refused, so one account cannot stand in for another. */
    val expectedUser: String? = null,
    /**
     * Optional PostgreSQL password file the account's current token is written to, so that psql, an IDE
     * or another client reaches the same databases without a sign-in of its own. Off when left out.
     */
    val pgpassFile: String? = null,
) {
    companion object {
        const val AZURE_CLI_CLIENT_ID = "04b07795-8ddb-461a-bbee-02f9e1bf7b46"
    }
}

/**
 * One configured environment of any kind. A null value among the overridable settings means
 * "not overridden" and is filled in from [EnvironmentDefaults]; it never means "unknown".
 *
 * Which credential fields are required depends on [type], so they are nullable here and checked
 * where the environment is built: a database needs a username and a password, Grafana a token.
 */
data class EnvironmentProperties(
    val description: String,
    /** Required: it selects how the environment is reached, so it is never guessed from the name. */
    val type: EnvironmentType,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val token: String? = null,
    /**
     * PEM file with the certificates a Grafana or Harbor instance's HTTPS endpoint is trusted by, replacing
     * the JVM's default authorities for that instance only. Databases configure TLS in their URL.
     */
    val caFile: String? = null,
    /** Restricts catalog lookups to one owner. Null means every schema the account can see. */
    val schema: String? = null,
    /** The Harbor project the tools work in and the probe checks the token against; required for HARBOR only. */
    val project: String? = null,
    val readOnly: Boolean = true,
    val defaultRows: Int? = null,
    val maxRows: Int? = null,
    val queryTimeoutSeconds: Int? = null,
    val maxResponseCharacters: Int? = null,
    val defaultTextValueCharacters: Int? = null,
    val maxTextValueCharacters: Int? = null,
    val maxSourceLines: Int? = null,
    val connectTimeoutSeconds: Int? = null,
    val socketReadTimeoutSeconds: Int? = null,
    val pool: PoolProperties = PoolProperties(),
    val authentication: AuthenticationMethod = AuthenticationMethod.PASSWORD,
    /** The entry under `mcp-hub.entra-accounts` to sign in with; required with [AuthenticationMethod.ENTRA] only. */
    val entraAccount: String? = null,
) {
    fun requireUsername(environmentName: String): String = checkNotNull(username) {
        "Environment $environmentName is a $type database and needs a username."
    }

    fun requirePassword(environmentName: String): String = checkNotNull(password) {
        "Environment $environmentName is a $type database and needs a password."
    }

    fun requireToken(environmentName: String): String = checkNotNull(token) {
        "Environment $environmentName is a $type instance and needs a token."
    }

    fun requireProject(environmentName: String): String = checkNotNull(project) {
        "Environment $environmentName is a Harbor registry and needs the project its tools work in."
    }

    override fun toString() =
        "EnvironmentProperties(description=$description, type=$type, readOnly=$readOnly, authentication=$authentication)"
}

data class PoolProperties(
    val maximumSize: Int? = null,
    val minimumIdle: Int? = null,
    val keepaliveSeconds: Long? = null,
    val validationTimeoutSeconds: Long? = null,
)

/**
 * Fully resolved environment. Deliberately carries no URL and no credentials, so
 * neither can reach a tool response, a status report or a log line.
 */
data class EnvironmentSettings(
    val name: String,
    val description: String,
    val type: EnvironmentType,
    val schema: String?,
    val readOnly: Boolean,
    val defaultRows: Int,
    val maxRows: Int,
    val queryTimeoutSeconds: Int,
    val maxResponseCharacters: Int,
    val defaultTextValueCharacters: Int,
    val maxTextValueCharacters: Int,
    val maxSourceLines: Int,
    val connectTimeoutSeconds: Int,
    val socketReadTimeoutSeconds: Int,
    val pool: PoolSettings,
    val entraAccount: String?,
)

data class PoolSettings(
    val maximumSize: Int,
    val minimumIdle: Int,
    val keepaliveSeconds: Long,
    val validationTimeoutSeconds: Long,
)
