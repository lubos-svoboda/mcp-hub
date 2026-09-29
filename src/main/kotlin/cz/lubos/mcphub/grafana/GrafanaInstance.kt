package cz.lubos.mcphub.grafana

import com.fasterxml.jackson.databind.ObjectMapper
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.http.HttpApi
import cz.lubos.mcphub.target.ProbeTarget
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

class GrafanaInstance(
    val settings: EnvironmentSettings,
    baseUrl: String,
    token: String,
    httpClient: HttpClient,
) : ProbeTarget {

    override val name: String get() = settings.name
    override val description: String get() = settings.description
    override val type: EnvironmentType get() = settings.type
    override val readOnly: Boolean get() = settings.readOnly

    private val api = HttpApi(
        serviceName = "Grafana",
        baseUrl = baseUrl,
        authorization = "Bearer $token",
        httpClient = httpClient,
        requestTimeout = Duration.ofSeconds(settings.socketReadTimeoutSeconds.toLong()),
    )
    private val objectMapper = ObjectMapper()

    /**
     * Asks for the datasources rather than the health endpoint, which Grafana answers without a token:
     * a deleted or expired token has to show as down, and the answer also tells whether the log tools
     * have a Loki datasource to work with.
     */
    override fun checkReachable(): String? {
        val response = api.send("GET", "/api/datasources", null)
        return when (response.statusCode()) {
            in 200..299 ->
                if (objectMapper.readTree(response.body()).any { it.path("type").asText() == "loki" }) {
                    null
                } else {
                    "No Loki datasource is visible to this token, so query_logs and list_log_labels cannot work."
                }

            401 -> throw IllegalStateException(
                "Grafana refused the token (HTTP 401): it may have been deleted or have expired.",
            )

            403 -> "The token may not list datasources (HTTP 403), so query_logs and list_log_labels cannot work."

            else -> throw IllegalStateException(
                "Grafana answered HTTP ${response.statusCode()} on /api/datasources: ${response.body().take(200)}",
            )
        }
    }

    fun request(method: String, endpoint: String, body: String?): String = api.request(method, endpoint, body)

    internal fun resolve(endpoint: String): URI = api.resolve(endpoint)
}
