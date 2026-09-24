package cz.lubos.mcphub.grafana

import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.target.ProbeTarget
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GrafanaInstance(
    val settings: EnvironmentSettings,
    baseUrl: String,
    private val token: String,
    private val httpClient: HttpClient,
) : ProbeTarget {

    override val name: String get() = settings.name
    override val description: String get() = settings.description
    override val type: EnvironmentType get() = settings.type
    override val readOnly: Boolean get() = settings.readOnly

    private val baseUri: URI = URI.create(baseUrl.trimEnd('/'))
    private val requestTimeout: Duration = Duration.ofSeconds(settings.socketReadTimeoutSeconds.toLong())

    override fun checkReachable() {
        val response = send("GET", "/api/health", null)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Grafana answered HTTP ${response.statusCode()} on /api/health")
        }
    }

    fun request(method: String, endpoint: String, body: String?): String {
        val response = send(method, endpoint, body)
        if (response.statusCode() in 300..399) {
            val location = response.headers().firstValue("Location").orElse("an unknown location")
            throw IllegalStateException(
                "Grafana redirected $method $endpoint to $location. Redirects are not followed, so the " +
                    "token never leaves the configured address; point the url at the final address instead.",
            )
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Grafana answered HTTP ${response.statusCode()} on $method $endpoint: " +
                    response.body().take(500),
            )
        }
        return response.body()
    }

    /**
     * The endpoint is appended to the configured address and must stay on it, because the token
     * travels with every request. Without this check `@evil.example/x` would turn everything
     * before it into a user name and send the token to evil.example.
     */
    internal fun resolve(endpoint: String): URI {
        require(endpoint.startsWith("/") && !endpoint.startsWith("//")) {
            "The endpoint must be a path starting with a single slash, for example /api/health."
        }
        val uri = URI.create(baseUri.toString() + endpoint)
        require(uri.scheme == baseUri.scheme && uri.rawAuthority == baseUri.rawAuthority) {
            "The endpoint must stay on ${baseUri.scheme}://${baseUri.rawAuthority}."
        }
        return uri
    }

    private fun send(method: String, endpoint: String, body: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(resolve(endpoint))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .apply {
                if (body == null) {
                    method(method, HttpRequest.BodyPublishers.noBody())
                } else {
                    header("Content-Type", "application/json")
                    method(method, HttpRequest.BodyPublishers.ofString(body))
                }
            }
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
