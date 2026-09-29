package cz.lubos.mcphub.harbor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.http.HttpApi
import cz.lubos.mcphub.target.ProbeTarget
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/** One version of an image as the tools report it: only what a question about it needs. */
data class ArtifactSummary(
    val digest: String,
    val tags: List<String>,
    val pushed: String?,
    /** Null when nobody has pulled it yet. */
    val lastPulled: String?,
    val sizeBytes: Long,
    val type: String?,
    /** Null when it was not scanned or the account may not see scan results. */
    val scan: ScanSummary?,
)

data class ScanSummary(
    val status: String?,
    val severity: String?,
    val total: Int?,
    val fixable: Int?,
    /** Vulnerabilities by severity, for example Critical, High, Medium and Low. */
    val bySeverity: Map<String, Int>,
)

/** A Harbor registry reached with a robot account, which signs in with its name and token. */
class HarborInstance(
    val settings: EnvironmentSettings,
    baseUrl: String,
    username: String,
    token: String,
    val project: String,
    httpClient: HttpClient,
) : ProbeTarget {

    override val name: String get() = settings.name
    override val description: String get() = settings.description
    override val type: EnvironmentType get() = settings.type
    override val readOnly: Boolean get() = settings.readOnly

    private val api = HttpApi(
        serviceName = "Harbor",
        baseUrl = baseUrl,
        authorization = "Basic " + Base64.getEncoder().encodeToString("$username:$token".toByteArray(StandardCharsets.UTF_8)),
        httpClient = httpClient,
        requestTimeout = Duration.ofSeconds(settings.socketReadTimeoutSeconds.toLong()),
    )
    private val objectMapper = ObjectMapper()

    /**
     * Lists one repository of the project rather than asking for the project itself: a robot account
     * usually may read the repositories of its project but not the project's details.
     */
    override fun checkReachable(): String? {
        val endpoint = "/api/v2.0/projects/${encodeOnce(project)}/repositories?page_size=1"
        val response = api.send("GET", endpoint, null)
        return when (response.statusCode()) {
            in 200..299 -> null

            401 -> throw IllegalStateException(
                "Harbor refused the credentials (HTTP 401): the robot account may have been deleted, " +
                    "disabled or have expired.",
            )

            403 -> "The account may not list the repositories of project $project (HTTP 403), so " +
                "list_harbor_artifacts cannot work."

            404 -> throw IllegalStateException("Harbor has no project $project, or the account cannot see it (HTTP 404).")

            else -> throw IllegalStateException(
                "Harbor answered HTTP ${response.statusCode()} on $endpoint: ${response.body().take(200)}",
            )
        }
    }

    fun request(endpoint: String): String = api.request("GET", endpoint, null)

    /** Newest first. [tagContaining] keeps the versions with a tag containing that text. */
    fun artifacts(repository: String, tagContaining: String?, limit: Int): List<ArtifactSummary> {
        val query = buildString {
            append("page_size=$limit&with_tag=true&with_scan_overview=true&sort=-push_time")
            tagContaining?.let { append("&q=").append(encodeOnce("tags=~$it")) }
        }
        val endpoint = "${repositoryPath(repository)}/artifacts?$query"
        val artifacts = objectMapper.readTree(request(endpoint)).map(::summarise)
        // Harbor answers a repository that does not exist with an empty list too.
        require(artifacts.isNotEmpty() || repositoryExists(repository)) {
            "Project $project has no repository ${repositoryName(repository)}. harbor_api_request with " +
                "/api/v2.0/projects/$project/repositories lists the repositories."
        }
        return artifacts
    }

    private fun repositoryExists(repository: String): Boolean {
        val query = encodeOnce("name=$project/${repositoryName(repository)}")
        return !objectMapper.readTree(request("/api/v2.0/projects/${encodeOnce(project)}/repositories?q=$query&page_size=1")).isEmpty
    }

    /**
     * Harbor splits the path at every slash, and the proxy in front of it decodes one level of
     * escaping, so a slash inside a repository name has to arrive as %252F. The project may be
     * given in front of the name or left out.
     */
    internal fun repositoryPath(repository: String): String =
        "/api/v2.0/projects/${encodeOnce(project)}/repositories/${encodeOnce(encodeOnce(repositoryName(repository)))}"

    private fun repositoryName(repository: String): String =
        repository.removePrefix("/").removePrefix("$project/").also { repositoryName ->
            require(repositoryName.isNotBlank()) { "Name a repository of project $project, for example payments/api." }
        }

    private fun summarise(artifact: JsonNode) = ArtifactSummary(
        digest = artifact.path("digest").asText(),
        tags = artifact.path("tags").map { it.path("name").asText() },
        pushed = artifact.textOrNull("push_time"),
        lastPulled = artifact.textOrNull("pull_time")?.takeUnless { it.startsWith(NEVER) },
        sizeBytes = artifact.path("size").asLong(),
        type = artifact.textOrNull("type"),
        // The report is keyed by its media type, which changes with the scanner's report version.
        scan = artifact.path("scan_overview").firstOrNull()?.let { report ->
            val summary = report.path("summary")
            ScanSummary(
                status = report.textOrNull("scan_status"),
                severity = report.textOrNull("severity"),
                total = summary.path("total").takeIf(JsonNode::isNumber)?.asInt(),
                fixable = summary.path("fixable").takeIf(JsonNode::isNumber)?.asInt(),
                bySeverity = summary.path("summary").properties().associate { (severity, count) -> severity to count.asInt() },
            )
        },
    )

    private fun JsonNode.textOrNull(field: String): String? = path(field).takeIf(JsonNode::isTextual)?.asText()

    private fun encodeOnce(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private companion object {
        /** Harbor's pull time of an artifact nobody has pulled. */
        const val NEVER = "0001-01-01"
    }
}
