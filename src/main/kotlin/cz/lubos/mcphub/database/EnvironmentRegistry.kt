package cz.lubos.mcphub.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.target.ProbeTarget
import cz.lubos.mcphub.target.TargetRegistry
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class EnvironmentRegistry(hubProperties: HubProperties) : TargetRegistry, AutoCloseable {

    private val environmentsByName: Map<String, DatabaseEnvironment> = build(hubProperties)

    val names: Set<String> get() = environmentsByName.keys

    fun all(): Collection<DatabaseEnvironment> = environmentsByName.values

    override fun targets(): Collection<ProbeTarget> = environmentsByName.values

    fun find(environmentName: String): DatabaseEnvironment? = environmentsByName[environmentName]

    /** Fails with the list of valid names, so a caller recovers without another round trip. */
    fun requireEnvironment(environmentName: String): DatabaseEnvironment =
        environmentsByName[environmentName] ?: throw IllegalArgumentException(
            if (names.isEmpty()) {
                "No database environment is configured on this server."
            } else {
                "Unknown environment '$environmentName'. Configured environments: ${names.sorted().joinToString()}."
            },
        )

    override fun close() = environmentsByName.values.forEach(DatabaseEnvironment::close)

    private fun build(hubProperties: HubProperties): Map<String, DatabaseEnvironment> {
        val settingsByName = hubProperties.resolveSettings()
        // Grafana environments live in the same map and are picked up by GrafanaRegistry instead.
        return hubProperties.environments
            .filterValues { properties -> properties.type.isDatabase }
            .mapValues { (environmentName, properties) ->
                require(properties.caFile == null) {
                    "Environment $environmentName sets ca-file, which only a Grafana instance uses. " +
                        "Configure TLS for a database in its JDBC URL instead."
                }
                validateAuthentication(environmentName, properties, hubProperties)
                val settings = settingsByName.getValue(environmentName)
                DatabaseEnvironment(settings, createDataSource(environmentName, properties, settings))
            }
    }

    private fun validateAuthentication(
        environmentName: String,
        properties: EnvironmentProperties,
        hubProperties: HubProperties,
    ) {
        when (properties.authentication) {
            AuthenticationMethod.PASSWORD -> require(properties.entraAccount == null) {
                "Environment $environmentName names entra-account ${properties.entraAccount} but signs in with " +
                    "a password. Set authentication: ENTRA to sign in with that account."
            }

            AuthenticationMethod.ENTRA -> {
                require(properties.type == EnvironmentType.POSTGRESQL) {
                    "Environment $environmentName is an ${properties.type} database, which cannot sign in with " +
                        "Microsoft Entra ID. Only POSTGRESQL environments can."
                }
                val definedAccounts = hubProperties.entraAccounts.keys.sorted().joinToString().ifEmpty { "none" }
                val accountName = requireNotNull(properties.entraAccount) {
                    "Environment $environmentName signs in with Microsoft Entra ID and needs entra-account. " +
                        "Accounts defined under mcp-hub.entra-accounts: $definedAccounts."
                }
                require(accountName in hubProperties.entraAccounts) {
                    "Environment $environmentName names entra-account $accountName, which is not defined under " +
                        "mcp-hub.entra-accounts. Defined accounts: $definedAccounts."
                }
                require(properties.password == null) {
                    "Environment $environmentName signs in with Microsoft Entra ID, so it takes no password."
                }
            }
        }
    }

    private fun createDataSource(
        environmentName: String,
        properties: EnvironmentProperties,
        settings: EnvironmentSettings,
    ): HikariDataSource {
        val configuration = HikariConfig().apply {
            poolName = settings.name
            jdbcUrl = properties.url
            username = properties.requireUsername(environmentName)
            password = properties.requirePassword(environmentName)

            // A database that cannot be reached must not stop the application from starting.
            // The pool keeps retrying on its own and ConnectionProbe reports what is wrong.
            initializationFailTimeout = -1

            maximumPoolSize = settings.pool.maximumSize
            minimumIdle = settings.pool.minimumIdle
            keepaliveTime = millis(settings.pool.keepaliveSeconds)
            validationTimeout = millis(settings.pool.validationTimeoutSeconds)
            connectionTimeout = millis(settings.connectTimeoutSeconds.toLong())
            applyDriverTimeouts(settings)
        }
        return HikariDataSource(configuration)
    }

    /**
     * The two drivers disagree on both the property names and the unit: Oracle counts
     * milliseconds, PostgreSQL seconds. Values must be strings either way, because the driver
     * reads them through Properties.getProperty, which returns null for anything else.
     */
    private fun HikariConfig.applyDriverTimeouts(settings: EnvironmentSettings) {
        when (settings.type) {
            EnvironmentType.ORACLE -> {
                addDataSourceProperty(
                    "oracle.net.CONNECT_TIMEOUT",
                    millis(settings.connectTimeoutSeconds.toLong()).toString(),
                )
                addDataSourceProperty(
                    "oracle.jdbc.ReadTimeout",
                    millis(settings.socketReadTimeoutSeconds.toLong()).toString(),
                )
            }

            EnvironmentType.POSTGRESQL -> {
                addDataSourceProperty("connectTimeout", settings.connectTimeoutSeconds.toString())
                addDataSourceProperty("socketTimeout", settings.socketReadTimeoutSeconds.toString())
            }

            // Unreachable: Grafana environments never reach a connection pool.
            EnvironmentType.GRAFANA ->
                error("${settings.name} is a Grafana instance and has no JDBC connection.")
        }
    }

    private fun millis(seconds: Long): Long = Duration.ofSeconds(seconds).toMillis()
}
