package cz.lubos.mcphub.status

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ReleaseCheckTest {

    private val requests = AtomicInteger()

    @ParameterizedTest(name = "{0} over {1}: {2}")
    @CsvSource(
        "0.5.1, 0.5.0, true",
        "0.10.0, 0.9.3, true",
        "1.0.0, 0.12.7, true",
        "0.5.0, 0.5.0, false",
        "0.4.1, 0.5.0, false",
        "0.4.1, 0.4.1-3-gabc1234-dirty, false",
        "0.5.0, 0.4.1-3-gabc1234-dirty, true",
        "0.5.0, unknown, false",
        "0.5.0, 0.0.0-dev, false",
    )
    fun `a release is newer only by its version numbers`(candidate: String, current: String, newer: Boolean) {
        assertThat(ReleaseCheck.isNewer(candidate, current)).isEqualTo(newer)
    }

    /** Shaped like GitHub's answer for /repos/{owner}/{repo}/releases/latest, trimmed to the fields read. */
    @Test
    fun `a newer release on GitHub is offered with its notes and publication time`() {
        val releaseCheck = withGitHubAnswering(200 to RELEASE_0_5_1, currentVersion = "0.5.0") { it.refresh() }

        assertThat(releaseCheck.newerRelease()).isEqualTo(
            LatestRelease(
                version = "0.5.1",
                url = "https://github.com/lubos-svoboda/mcp-hub/releases/tag/v0.5.1",
                publishedAt = Instant.parse("2026-10-21T08:00:00Z"),
            ),
        )
    }

    @Test
    fun `the running release is not offered to itself`() {
        val releaseCheck = withGitHubAnswering(200 to RELEASE_0_5_1, currentVersion = "0.5.1") { it.refresh() }

        assertThat(releaseCheck.newerRelease()).isNull()
    }

    @Test
    fun `a failed check keeps the release known before`() {
        val releaseCheck = withGitHubAnswering(
            200 to RELEASE_0_5_1,
            403 to """{"message":"API rate limit exceeded"}""",
            currentVersion = "0.5.0",
        ) { releaseCheck ->
            releaseCheck.refresh()
            releaseCheck.refresh()
        }

        assertThat(requests.get()).isEqualTo(2)

        assertThat(releaseCheck.newerRelease()?.version).isEqualTo("0.5.1")
    }

    @Test
    fun `a disabled check never asks GitHub`() {
        withGitHubAnswering(200 to RELEASE_0_5_1, currentVersion = "0.5.0", enabled = false) { it.refresh() }

        assertThat(requests.get()).isZero()
    }

    @Test
    fun `a release links to its own page and a local build to the repository`() {
        assertThat(ReleaseCheck("0.5.0", null, enabled = false).currentVersionUrl)
            .isEqualTo("https://github.com/lubos-svoboda/mcp-hub/releases/tag/v0.5.0")
        assertThat(ReleaseCheck("0.4.1-3-gabc1234-dirty", null, enabled = false).currentVersionUrl)
            .isEqualTo("https://github.com/lubos-svoboda/mcp-hub")
    }

    /** Answers each request with the next of [answers], repeating the last one. */
    private fun withGitHubAnswering(
        vararg answers: Pair<Int, String>,
        currentVersion: String? = null,
        enabled: Boolean = true,
        check: (ReleaseCheck) -> Unit,
    ): ReleaseCheck {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val (status, body) = answers[minOf(requests.getAndIncrement(), answers.lastIndex)]
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val uri = URI.create("http://127.0.0.1:${server.address.port}/repos/lubos-svoboda/mcp-hub/releases/latest")
            val releaseCheck = ReleaseCheck(currentVersion, null, enabled, uri)
            check(releaseCheck)
            return releaseCheck
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        val RELEASE_0_5_1 = """
            {
              "tag_name": "v0.5.1",
              "html_url": "https://github.com/lubos-svoboda/mcp-hub/releases/tag/v0.5.1",
              "published_at": "2026-10-21T08:00:00Z",
              "draft": false,
              "prerelease": false
            }
        """.trimIndent()
    }
}
