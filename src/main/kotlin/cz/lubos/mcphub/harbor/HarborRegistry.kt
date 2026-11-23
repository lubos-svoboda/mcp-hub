package cz.lubos.mcphub.harbor

import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.http.HttpApi
import cz.lubos.mcphub.http.HttpTargetRegistry
import org.springframework.stereotype.Component

@Component
class HarborRegistry(hubProperties: HubProperties) :
    HttpTargetRegistry<HarborInstance>(hubProperties, EnvironmentType.HARBOR, "Harbor registry", "registries") {

    override fun create(environmentName: String, properties: EnvironmentProperties, settings: EnvironmentSettings): HarborInstance {
        requireReadOnly(environmentName, properties)
        return HarborInstance(
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
