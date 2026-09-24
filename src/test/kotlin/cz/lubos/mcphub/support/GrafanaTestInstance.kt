package cz.lubos.mcphub.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import cz.lubos.mcphub.config.EnvironmentDefaults
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.config.ProbeProperties
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Grafana with a Loki datasource, shared by every integration test like [OracleTestDatabase]. One
 * log line is pushed to Loki before any test runs, and a Viewer service account supplies the token.
 */
object GrafanaTestInstance {

    const val ENVIRONMENT_NAME = "GRAFANA_TEST"
    const val LOG_APP = "hub-test"
    const val LOG_LINE = "Objednávka 42 selhala: vypršel časový limit"

    private const val ADMIN_PASSWORD = "grafana-admin-do-not-leak"
    private const val LOKI_ALIAS = "loki"

    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newHttpClient()
    private val network = Network.newNetwork()

    private val loki =
        GenericContainer(DockerImageName.parse("grafana/loki:3.5.3"))
            .withNetwork(network)
            .withNetworkAliases(LOKI_ALIAS)
            .withExposedPorts(3100)
            .waitingFor(Wait.forHttp("/ready").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))

    private val grafana =
        GenericContainer(DockerImageName.parse("grafana/grafana:12.1.1"))
            .withNetwork(network)
            .withEnv("GF_SECURITY_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withExposedPorts(3000)
            .waitingFor(Wait.forHttp("/api/health").forStatusCode(200))

    val url: String by lazy { "http://${grafana.host}:${grafana.getMappedPort(3000)}" }
    val viewerToken: String

    init {
        loki.start()
        grafana.start()
        pushLogLine()
        awaitLogLine()
        createLokiDatasource()
        viewerToken = createViewerToken()
    }

    fun hubProperties(readOnly: Boolean = true) = HubProperties(
        probe = ProbeProperties(intervalSeconds = 3_600),
        defaults = EnvironmentDefaults(connectTimeoutSeconds = 5, socketReadTimeoutSeconds = 30),
        environments = mapOf(
            ENVIRONMENT_NAME to EnvironmentProperties(
                description = "Integration test Grafana",
                type = EnvironmentType.GRAFANA,
                url = url,
                token = viewerToken,
                readOnly = readOnly,
            ),
        ),
    )

    private val lokiUrl: String get() = "http://${loki.host}:${loki.getMappedPort(3100)}"

    private fun pushLogLine() {
        val body = objectMapper.writeValueAsString(
            mapOf(
                "streams" to listOf(
                    mapOf(
                        "stream" to mapOf("app" to LOG_APP),
                        "values" to listOf(listOf("${Instant.now().toEpochMilli()}000000", LOG_LINE)),
                    ),
                ),
            ),
        )
        send(HttpRequest.newBuilder(URI.create("$lokiUrl/loki/api/v1/push")), "POST", body)
    }

    /** Loki accepts a push before the line can be queried, so the tests start once it can. */
    private fun awaitLogLine() {
        val query = URLEncoder.encode("{app=\"$LOG_APP\"}", StandardCharsets.UTF_8)
        val deadline = Instant.now().plusSeconds(60)
        while (Instant.now().isBefore(deadline)) {
            val answer = send(
                HttpRequest.newBuilder(URI.create("$lokiUrl/loki/api/v1/query_range?query=$query")),
                "GET",
                null,
            )
            if (!answer.path("data").path("result").isEmpty) {
                return
            }
            Thread.sleep(500)
        }
        error("Loki did not return the pushed log line within 60 s")
    }

    private fun createLokiDatasource() {
        adminRequest(
            "/api/datasources",
            mapOf("name" to "Loki", "type" to "loki", "access" to "proxy", "url" to "http://$LOKI_ALIAS:3100"),
        )
    }

    private fun createViewerToken(): String {
        val serviceAccount = adminRequest("/api/serviceaccounts", mapOf("name" to "hub", "role" to "Viewer"))
        val token = adminRequest(
            "/api/serviceaccounts/${serviceAccount.path("id").asLong()}/tokens",
            mapOf("name" to "hub"),
        )
        return token.path("key").asText()
    }

    private fun adminRequest(path: String, body: Map<String, String>): JsonNode {
        val credentials = Base64.getEncoder().encodeToString("admin:$ADMIN_PASSWORD".toByteArray())
        return send(
            HttpRequest.newBuilder(URI.create("$url$path")).header("Authorization", "Basic $credentials"),
            "POST",
            objectMapper.writeValueAsString(body),
        )
    }

    private fun send(builder: HttpRequest.Builder, method: String, body: String?): JsonNode {
        val request = builder
            .header("Content-Type", "application/json")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "$method ${request.uri().path} answered HTTP ${response.statusCode()}: ${response.body()}"
        }
        return if (response.body().isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(response.body())
    }
}
