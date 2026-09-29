package cz.lubos.mcphub.grafana

import cz.lubos.mcphub.config.AuthenticationMethod
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.http.HttpApi
import cz.lubos.mcphub.target.ProbeTarget
import cz.lubos.mcphub.target.TargetRegistry
import org.springframework.stereotype.Component

/** Reads the same environment map as the databases do, taking the Grafana ones. */
@Component
class GrafanaRegistry(hubProperties: HubProperties) : TargetRegistry {

    private val instancesByName: Map<String, GrafanaInstance> = build(hubProperties)

    val names: Set<String> get() = instancesByName.keys

    fun all(): Collection<GrafanaInstance> = instancesByName.values

    override fun targets(): Collection<ProbeTarget> = instancesByName.values

    /** Fails with the list of valid names, so a caller recovers without another round trip. */
    fun requireInstance(instanceName: String): GrafanaInstance =
        instancesByName[instanceName] ?: throw IllegalArgumentException(
            if (names.isEmpty()) {
                "No Grafana instance is configured on this server."
            } else {
                "Unknown Grafana instance '$instanceName'. Configured instances: ${names.sorted().joinToString()}."
            },
        )

    private fun build(hubProperties: HubProperties): Map<String, GrafanaInstance> {
        val settingsByName = hubProperties.resolveSettings()
        return hubProperties.environments
            .filterValues { properties -> properties.type == EnvironmentType.GRAFANA }
            .mapValues { (environmentName, properties) ->
                require(properties.authentication == AuthenticationMethod.PASSWORD && properties.entraAccount == null) {
                    "Environment $environmentName is a Grafana instance, which signs in with its token only, " +
                        "not with Microsoft Entra ID."
                }
                val settings = settingsByName.getValue(environmentName)
                GrafanaInstance(
                    settings = settings,
                    baseUrl = properties.url,
                    token = properties.requireToken(environmentName),
                    httpClient = HttpApi.createHttpClient(properties.caFile, settings.connectTimeoutSeconds),
                )
            }
    }
}
