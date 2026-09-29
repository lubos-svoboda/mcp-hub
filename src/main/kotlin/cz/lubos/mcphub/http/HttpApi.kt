package cz.lubos.mcphub.http

import cz.lubos.mcphub.grafana.sslContextTrusting
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * An HTTP API reached with a credential that travels with every request. Every endpoint therefore
 * stays on the configured address, and the client given here must not follow redirects.
 */
class HttpApi(
    private val serviceName: String,
    baseUrl: String,
    private val authorization: String,
    private val httpClient: HttpClient,
    private val requestTimeout: Duration,
) {

    val baseUri: URI = URI.create(baseUrl.trimEnd('/'))

    /** Returns the answer of a successful request and fails with the service's own words otherwise. */
    fun request(method: String, endpoint: String, body: String?): String {
        val response = send(method, endpoint, body)
        if (response.statusCode() in 300..399) {
            val location = response.headers().firstValue("Location").orElse("an unknown location")
            throw IllegalStateException(
                "$serviceName redirected $method $endpoint to $location. Redirects are not followed, so the " +
                    "credentials never leave the configured address; point the url at the final address instead.",
            )
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "$serviceName answered HTTP ${response.statusCode()} on $method $endpoint: " +
                    response.body().take(500),
            )
        }
        return response.body()
    }

    /**
     * The endpoint is appended to the configured address and must stay on it. Without this check
     * `@evil.example/x` would turn everything before it into a user name and send the credentials
     * to evil.example.
     */
    fun resolve(endpoint: String): URI {
        require(endpoint.startsWith("/") && !endpoint.startsWith("//")) {
            "The endpoint must be a path starting with a single slash, for example /api/health."
        }
        val uri = URI.create(baseUri.toString() + endpoint)
        require(uri.scheme == baseUri.scheme && uri.rawAuthority == baseUri.rawAuthority) {
            "The endpoint must stay on ${baseUri.scheme}://${baseUri.rawAuthority}."
        }
        return uri
    }

    fun send(method: String, endpoint: String, body: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(resolve(endpoint))
            .timeout(requestTimeout)
            .header("Authorization", authorization)
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
            throw IOException("$serviceName at $baseUri could not be reached: ${describe(failure)}", failure)
        }
    }

    private fun describe(failure: Throwable): String =
        generateSequence(failure) { it.cause }
            .map { it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName }
            .distinct()
            .joinToString(": ")

    companion object {
        /** One client per instance, because each may trust its own certificate authority. */
        fun createHttpClient(caFile: String?, connectTimeoutSeconds: Int): HttpClient =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds.toLong()))
                // A redirect could carry the credentials to another host, so none is followed.
                .followRedirects(HttpClient.Redirect.NEVER)
                .apply { caFile?.let { sslContext(sslContextTrusting(Path.of(it))) } }
                .build()
    }
}
