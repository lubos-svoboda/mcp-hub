package cz.lubos.mcphub.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("mcp-hub")
data class HubProperties(
    val probe: ProbeProperties = ProbeProperties(),
    val defaults: EnvironmentDefaults = EnvironmentDefaults(),
    val environments: Map<String, EnvironmentProperties> = emptyMap(),
    val entraAccounts: Map<String, EntraAccountProperties> = emptyMap(),
    /** Host names the server answers to. Anything else is refused, so a web page cannot reach it by DNS rebinding. */
    val allowedHosts: List<String> = listOf("localhost", "127.0.0.1", "::1"),
) {
    fun resolveSettings(): Map<String, EnvironmentSettings> =
        environments.mapValues { (name, environment) -> resolveEnvironment(name, environment) }

    private fun resolveEnvironment(name: String, environment: EnvironmentProperties) =
        EnvironmentSettings(
            name = name,
            description = environment.description,
            type = environment.type,
            schema = environment.schema,
            readOnly = environment.readOnly,
            maxRows = environment.maxRows ?: defaults.maxRows,
            queryTimeoutSeconds = environment.queryTimeoutSeconds ?: defaults.queryTimeoutSeconds,
            maxResponseCharacters = environment.maxResponseCharacters ?: defaults.maxResponseCharacters,
            maxTextValueCharacters = environment.maxTextValueCharacters ?: defaults.maxTextValueCharacters,
            maxSourceLines = environment.maxSourceLines ?: defaults.maxSourceLines,
            connectTimeoutSeconds = environment.connectTimeoutSeconds ?: defaults.connectTimeoutSeconds,
            socketReadTimeoutSeconds = environment.socketReadTimeoutSeconds ?: defaults.socketReadTimeoutSeconds,
            pool = resolvePool(environment.pool),
            entraAccount = environment.entraAccount,
        )

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
    val maxRows: Int = 500,
    val queryTimeoutSeconds: Int = 60,
    val maxResponseCharacters: Int = 100_000,
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
    ;

    val isDatabase: Boolean get() = this != GRAFANA
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
     * PEM file with the certificates a Grafana instance's HTTPS endpoint is trusted by, replacing
     * the JVM's default authorities for that instance only. Databases configure TLS in their URL.
     */
    val caFile: String? = null,
    /** Restricts catalog lookups to one owner. Null means every schema the account can see. */
    val schema: String? = null,
    val readOnly: Boolean = true,
    val maxRows: Int? = null,
    val queryTimeoutSeconds: Int? = null,
    val maxResponseCharacters: Int? = null,
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
        "Environment $environmentName is a Grafana instance and needs a token."
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
    val maxRows: Int,
    val queryTimeoutSeconds: Int,
    val maxResponseCharacters: Int,
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
