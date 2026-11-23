package cz.lubos.mcphub.artifactory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import cz.lubos.mcphub.config.EnvironmentSettings
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.http.HttpApi
import cz.lubos.mcphub.target.ProbeTarget
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration

/** One tag of an image as the tools report it. */
data class DockerTag(
    val tag: String,
    /**
     * When Artifactory stored the tag's manifest: in a local repository when it was pushed, in a remote
     * repository's cache when somebody first pulled it through Artifactory, not when it was released.
     */
    val created: String?,
    /** The digest `docker pull` reports, of the image's manifest or of its multi-platform index. */
    val digest: String,
)

data class MavenVersion(
    val version: String,
    /** True for a snapshot (integration) version. */
    val integration: Boolean,
)

/** A JFrog Artifactory reached with an access token, for example a personal one from `jf login`. */
class ArtifactoryInstance(
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
        serviceName = "Artifactory",
        baseUrl = baseUrl,
        authorization = "Bearer $token",
        httpClient = httpClient,
        requestTimeout = Duration.ofSeconds(settings.socketReadTimeoutSeconds.toLong()),
        // Some endpoints, such as /artifactory/api/search/latestVersion, answer only in plain text.
        accept = "${HttpApi.JSON}, */*;q=0.8",
    )
    private val objectMapper = ObjectMapper()

    /** Lists the repositories rather than pinging, because Artifactory answers a ping without a token (measured on artifactory.cetin). */
    override fun checkReachable(): String? {
        val response = api.send("GET", REPOSITORIES_ENDPOINT, null)
        return when (response.statusCode()) {
            in 200..299 -> {
                // The web interface and a sign-in page in front of Artifactory answer 200 too, but with HTML.
                val contentType = response.headers().firstValue("Content-Type").orElse("none")
                check("json" in contentType) {
                    "Artifactory answered $REPOSITORIES_ENDPOINT with $contentType instead of JSON; the url must be " +
                        "the base address Artifactory's REST API lives under, for example https://artifactory.example.internal/."
                }
                "The token may read no repository.".takeIf { objectMapper.readTree(response.body()).isEmpty }
            }

            401 -> throw IllegalStateException(
                "Artifactory refused the token (HTTP 401): it may have expired, as a personal token does " +
                    "after a year, or have been revoked.",
            )

            else -> throw IllegalStateException(
                "Artifactory answered HTTP ${response.statusCode()} on $REPOSITORIES_ENDPOINT: ${response.body().take(200)}",
            )
        }
    }

    fun request(endpoint: String): String = api.request("GET", endpoint, null)

    /** AQL only reads, but Artifactory takes it as the body of a POST, and only as plain text. */
    fun aql(query: String): String = api.request("POST", AQL_ENDPOINT, query, contentType = "text/plain; charset=utf-8")

    /**
     * Newest stored first. Artifactory keeps every tag as a folder of the image holding its manifest, and
     * the registry API lists tags without a date, so the manifests are searched for with AQL.
     */
    fun dockerTags(repository: String, image: String, tagContaining: String?, limit: Int): List<DockerTag> {
        val imagePath = image.trim('/')
        require(repository.isNotBlank() && imagePath.isNotBlank()) {
            "Name a repository and an image in it, for example crisp and crisp/backend."
        }
        // A client may send a left-out filter as an empty text.
        val tagFilter = tagContaining?.takeIf(String::isNotBlank)
        val results = objectMapper.readTree(aql(dockerTagsQuery(repository, imagePath, tagFilter, limit))).path("results")
        val tags = results.map { manifest ->
            DockerTag(
                tag = manifest.path("path").asText().removePrefix("$imagePath/"),
                created = manifest.path("created").takeIf(JsonNode::isTextual)?.asText(),
                digest = "sha256:" + manifest.path("sha256").asText(),
            )
        }
        // With a tag filter, no match is an answer; without one, the image or repository was named wrong.
        require(tags.isNotEmpty() || tagFilter != null) {
            "Repository $repository has no image $imagePath, or the token may not read it. A remote repository " +
                "is searched as its cache, for example docker-hub-cache. artifactory_api_request with " +
                "/artifactory/api/docker/$repository/v2/_catalog lists the images."
        }
        return tags
    }

    /**
     * Without the repository, include and the name, path and repo fields, Artifactory refuses AQL from an
     * account that is not an administrator (measured on artifactory.cetin). The values go through Jackson,
     * so a quote in them cannot change the query.
     */
    internal fun dockerTagsQuery(repository: String, imagePath: String, tagContaining: String?, limit: Int): String {
        val tagPattern = tagContaining?.let { "*$it*" } ?: "*"
        val criteria = objectMapper.createObjectNode().apply {
            put("repo", repository)
            putArray("\$or").apply {
                MANIFEST_NAMES.forEach { addObject().put("name", it) }
            }
            putArray("\$and").apply {
                addObject().pathCriterion("\$match", "$imagePath/$tagPattern")
                // Neither an image nested below this one nor the per-platform manifests of a multi-platform image.
                addObject().pathCriterion("\$nmatch", "$imagePath/*/*")
                addObject().pathCriterion("\$nmatch", "$imagePath/sha256__*")
            }
        }
        return "items.find(${objectMapper.writeValueAsString(criteria)})" +
            ".include(\"repo\",\"path\",\"name\",\"created\",\"sha256\")" +
            ".sort({\"\$desc\":[\"created\"]}).limit($limit)"
    }

    private fun ObjectNode.pathCriterion(operator: String, pattern: String) {
        putObject("path").put(operator, pattern)
    }

    /** In the order Artifactory gives them, which on artifactory.cetin is newest first. */
    fun mavenVersions(groupId: String, artifactId: String, repository: String?): List<MavenVersion> {
        val endpoint = buildString {
            append("/artifactory/api/search/versions?g=").append(encode(groupId)).append("&a=").append(encode(artifactId))
            // A client may send a left-out repository as an empty text.
            repository?.takeIf(String::isNotBlank)?.let { append("&repos=").append(encode(it)) }
        }
        val response = api.send("GET", endpoint, null)
        // Artifactory answers 404 both for an unknown artifact and for an unknown repository, each with its own message.
        require(response.statusCode() != 404) {
            "No version of $groupId:$artifactId found: ${errorMessage(response.body())}. A remote repository, such " +
                "as Maven Central behind a virtual one, knows only the versions somebody has already downloaded " +
                "through Artifactory."
        }
        return objectMapper.readTree(api.successBody(response, "GET", endpoint)).path("results").map { version ->
            MavenVersion(version = version.path("version").asText(), integration = version.path("integration").asBoolean())
        }
    }

    private fun errorMessage(body: String): String =
        runCatching { objectMapper.readTree(body).path("errors").path(0).path("message").asText() }
            .getOrNull()?.takeIf(String::isNotBlank) ?: body.take(200)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val REPOSITORIES_ENDPOINT = "/artifactory/api/repositories"
        const val AQL_ENDPOINT = "/artifactory/api/search/aql"

        /** A single-platform image keeps manifest.json in its tag folder, a multi-platform one list.manifest.json. */
        val MANIFEST_NAMES = listOf("manifest.json", "list.manifest.json")
    }
}
