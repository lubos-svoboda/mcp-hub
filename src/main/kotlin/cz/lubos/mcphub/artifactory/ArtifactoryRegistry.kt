package cz.lubos.mcphub.artifactory

import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.http.HttpApi
import cz.lubos.mcphub.http.HttpTargetRegistry
import org.springframework.stereotype.Component

@Component
class ArtifactoryRegistry(hubProperties: HubProperties) :
    HttpTargetRegistry<ArtifactoryInstance>(hubProperties, EnvironmentType.ARTIFACTORY, "Artifactory instance", "instances") {

    override fun create(environmentName: String, properties: EnvironmentProperties, settings: EnvironmentSettings): ArtifactoryInstance {
        requireReadOnly(environmentName, properties)
        // Copied from a Harbor entry, they would look like a limit that nothing enforces.
        require(properties.username == null && properties.project == null) {
            "Environment $environmentName is an Artifactory instance, which takes neither username nor project: " +
                "the token alone decides what it may read."
        }
        return ArtifactoryInstance(
            settings = settings,
            baseUrl = properties.url,
            token = properties.requireToken(environmentName),
            httpClient = HttpApi.createHttpClient(properties.caFile, settings.connectTimeoutSeconds),
        )
    }
}
