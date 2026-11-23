package cz.lubos.mcphub.http

import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.target.ProbeTarget
import cz.lubos.mcphub.target.TargetRegistry

/**
 * Reads the same environment map as the databases do, taking the environments of one [type] that
 * are reached over HTTP with a credential of their own.
 */
abstract class HttpTargetRegistry<T : ProbeTarget>(
    hubProperties: HubProperties,
    private val type: EnvironmentType,
    /** How messages name one environment, for example "Harbor registry". */
    private val kind: String,
    /** How messages name several, for example "registries". */
    private val kindPlural: String,
) : TargetRegistry {

    /**
     * Built in the constructor, so that a wrong configuration stops the start. [create] therefore runs
     * before a subclass is initialised and must use nothing but its arguments.
     */
    private val instancesByName: Map<String, T> = build(hubProperties)

    val names: Set<String> get() = instancesByName.keys

    fun all(): Collection<T> = instancesByName.values

    override fun targets(): Collection<ProbeTarget> = instancesByName.values

    /** Fails with the list of valid names, so a caller recovers without another round trip. */
    fun requireInstance(instanceName: String): T =
        instancesByName[instanceName] ?: throw IllegalArgumentException(
            if (names.isEmpty()) {
                "No $kind is configured on this server."
            } else {
                "Unknown $kind '$instanceName'. Configured $kindPlural: ${names.sorted().joinToString()}."
            },
        )

    /** Builds one instance after the checks every kind shares have passed. */
    protected abstract fun create(environmentName: String, properties: EnvironmentProperties, settings: EnvironmentSettings): T

    /** For a kind whose tools only read, where a writable environment would claim something untrue. */
    protected fun requireReadOnly(environmentName: String, properties: EnvironmentProperties) =
        require(properties.readOnly) {
            "Environment $environmentName is a $kind, whose tools only read; leave read-only out."
        }

    private fun build(hubProperties: HubProperties): Map<String, T> {
        val settingsByName = hubProperties.resolveSettings()
        return hubProperties.environments
            .filterValues { properties -> properties.type == type }
            .mapValues { (environmentName, properties) ->
                require(properties.authentication == AuthenticationMethod.PASSWORD && properties.entraAccount == null) {
                    "Environment $environmentName is a $kind, which signs in with its own token only, " +
                        "not with Microsoft Entra ID."
                }
                create(environmentName, properties, settingsByName.getValue(environmentName))
            }
    }
}
