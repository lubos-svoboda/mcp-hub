package cz.lubos.mcphub.grafana

import com.fasterxml.jackson.databind.ObjectMapper
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.target.ProbeTarget
import java.io.IOException
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
    private val objectMapper = ObjectMapper()

    /**
     * Asks for the datasources rather than the health endpoint, which Grafana answers without a token:
     * a deleted or expired token has to show as down, and the answer also tells whether the log tools
     * have a Loki datasource to work with.
     */
    override fun checkReachable(): String? {
        val response = send("GET", "/api/datasources", null)
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
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (failure: IOException) {
            // The HTTP client reports an unreachable host without any message, not even the address.
            throw IOException("Grafana at $baseUri could not be reached: ${describe(failure)}", failure)
        }
    }

    private fun describe(failure: Throwable): String =
        generateSequence(failure) { it.cause }
            .map { it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
            .distinct()
            .joinToString(": ")
}
