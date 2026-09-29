package cz.lubos.mcphub.harbor

import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.http.HttpApi
import cz.lubos.mcphub.target.ProbeTarget
import cz.lubos.mcphub.target.TargetRegistry
import org.springframework.stereotype.Component

/** Reads the same environment map as the databases do, taking the Harbor ones. */
@Component
class HarborRegistry(hubProperties: HubProperties) : TargetRegistry {

    private val instancesByName: Map<String, HarborInstance> = build(hubProperties)

    val names: Set<String> get() = instancesByName.keys

    override fun targets(): Collection<ProbeTarget> = instancesByName.values

    /** Fails with the list of valid names, so a caller recovers without another round trip. */
    fun requireInstance(instanceName: String): HarborInstance =
        instancesByName[instanceName] ?: throw IllegalArgumentException(
            if (names.isEmpty()) {
                "No Harbor registry is configured on this server."
            } else {
                "Unknown Harbor registry '$instanceName'. Configured registries: ${names.sorted().joinToString()}."
            },
        )

    private fun build(hubProperties: HubProperties): Map<String, HarborInstance> {
        val settingsByName = hubProperties.resolveSettings()
        return hubProperties.environments
            .filterValues { properties -> properties.type == EnvironmentType.HARBOR }
            .mapValues { (environmentName, properties) ->
                require(properties.authentication == AuthenticationMethod.PASSWORD && properties.entraAccount == null) {
                    "Environment $environmentName is a Harbor registry, which signs in with a robot account " +
                        "only, not with Microsoft Entra ID."
                }
                // The tools only read, so a writable Harbor environment would claim something untrue.
                require(properties.readOnly) {
                    "Environment $environmentName is a Harbor registry, whose tools only read; leave read-only out."
                }
                val settings = settingsByName.getValue(environmentName)
                HarborInstance(
                    settings = settings,
                    baseUrl = properties.url,
                    username = checkNotNull(properties.username) {
                        "Environment $environmentName is a Harbor registry and needs the robot account as username."
                    },
                    token = properties.requireToken(environmentName),
                    project = properties.requireProject(environmentName),
                    httpClient = HttpApi.createHttpClient(properties.caFile, settings.connectTimeoutSeconds),
                )
            }
    }
}
