package cz.lubos.mcphub.grafana

import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.http.HttpApi
import cz.lubos.mcphub.http.HttpTargetRegistry
import org.springframework.stereotype.Component

@Component
class GrafanaRegistry(hubProperties: HubProperties) :
    HttpTargetRegistry<GrafanaInstance>(hubProperties, EnvironmentType.GRAFANA, "Grafana instance", "instances") {

    override fun create(environmentName: String, properties: EnvironmentProperties, settings: EnvironmentSettings): GrafanaInstance =
        GrafanaInstance(
            settings = settings,
            baseUrl = properties.url,
            token = properties.requireToken(environmentName),
            httpClient = HttpApi.createHttpClient(properties.caFile, settings.connectTimeoutSeconds),
        )
}
