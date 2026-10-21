package cz.lubos.mcphub.status

import com.fasterxml.jackson.databind.ObjectMapper
import cz.lubos.mcphub.config.HubProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

data class LatestRelease(
    val version: String,
    val url: String,
    val publishedAt: Instant?,
)

/** Knows which version runs and asks GitHub for the latest release, so the status page can offer a newer one. */
@Component
class ReleaseCheck internal constructor(
    /** Null when the jar carries no build information, as in a test run from the IDE. */
    val currentVersion: String?,
    val builtAt: Instant?,
    private val enabled: Boolean,
    private val latestReleaseUri: URI = LATEST_RELEASE_URI,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) {

    @Autowired
    constructor(hubProperties: HubProperties, buildProperties: ObjectProvider<BuildProperties>) : this(
        currentVersion = buildProperties.ifAvailable?.version,
        builtAt = buildProperties.ifAvailable?.time,
        enabled = hubProperties.updateCheck,
    )

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val objectMapper = ObjectMapper()

    @Volatile
    private var latestRelease: LatestRelease? = null

    @Volatile
    private var lastFailure: String? = null

    /** A release's own page when this is one, otherwise the repository. */
    val currentVersionUrl: String
        get() = currentVersion?.takeIf { RELEASE_VERSION.matches(it) }?.let { "$REPOSITORY_URL/releases/tag/v$it" } ?: REPOSITORY_URL

    fun newerRelease(): LatestRelease? = latestRelease?.takeIf { isNewer(it.version, currentVersion) }

    /** A failure leaves the last known release in place and is logged once, not at every check. */
    @Scheduled(initialDelay = 10, fixedDelay = 6 * 60 * 60, timeUnit = TimeUnit.SECONDS)
    fun refresh() {
        if (!enabled) {
            return
        }
        try {
            val request = HttpRequest.newBuilder(latestReleaseUri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "mcp-hub")
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "GitHub answered HTTP ${response.statusCode()}" }
            val release = objectMapper.readTree(response.body())
            val found = LatestRelease(
                version = release.path("tag_name").asText().removePrefix("v"),
                url = release.path("html_url").asText(),
                publishedAt = release.path("published_at").takeIf { it.isTextual }?.let { Instant.parse(it.asText()) },
            )
            if (found != latestRelease && isNewer(found.version, currentVersion)) {
                logger.info("MCP Hub {} is available, this is {}: {}", found.version, currentVersion, found.url)
            }
            latestRelease = found
            lastFailure = null
        } catch (failure: Exception) {
            val reason = failure.message ?: failure.javaClass.simpleName
            if (reason != lastFailure) {
                logger.info("Could not ask GitHub for the latest MCP Hub release: {}", reason)
            }
            lastFailure = reason
        }
    }

    internal companion object {
        const val REPOSITORY_URL = "https://github.com/lubos-svoboda/mcp-hub"
        val LATEST_RELEASE_URI: URI = URI.create("https://api.github.com/repos/lubos-svoboda/mcp-hub/releases/latest")
        val RELEASE_VERSION = Regex("""\d+\.\d+\.\d+""")
        /** What Gradle reports without a git tag, for example in a Docker build from a working copy. */
        private const val UNKNOWN_VERSION = "0.0.0"
        private val LEADING_VERSION =Regex("""^v?(\d+)\.(\d+)\.(\d+)""")

        /** A local build such as 0.4.1-3-gabc1234-dirty counts as 0.4.1; an unknown version, 0.0.0-dev included, is never outdated. */
        fun isNewer(candidate: String, current: String?): Boolean {
            val candidateParts = parts(candidate) ?: return false
            val currentParts = current?.takeUnless { it.startsWith(UNKNOWN_VERSION) }?.let(::parts) ?: return false
            return candidateParts.zip(currentParts).firstOrNull { (left, right) -> left != right }
                ?.let { (left, right) -> left > right } ?: false
        }

        private fun parts(version: String): List<Int>? =
            LEADING_VERSION.find(version)?.groupValues?.drop(1)?.map(String::toInt)
    }
}
