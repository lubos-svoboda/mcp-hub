package cz.lubos.mcphub.harbor

import com.sun.net.httpserver.HttpServer
import cz.lubos.mcphub.config.EnvironmentProperties
import cz.lubos.mcphub.config.EnvironmentType
import cz.lubos.mcphub.config.HubProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.Base64

/**
 * Harbor itself cannot run in a test, so a local server stands in for it. The answers follow the
 * shape of Harbor's API v2.0; that a real Harbor finds a repository by the doubly escaped name was
 * checked by hand against one, which this server cannot prove.
 */
class HarborInstanceTest {

    private val requests = mutableListOf<URI>()
    private val authorizations = mutableListOf<String?>()

    @Test
    fun `the robot account signs in with its name and token in a basic authorization header`() {
        withHarborAnswering(200, "[]") { harbor -> harbor.checkReachable() }

        val expected = "Basic " + Base64.getEncoder().encodeToString("$ROBOT:$TOKEN".toByteArray())
        assertThat(authorizations).containsOnly(expected)
    }

    @Test
    fun `the probe lists one repository of the project, which a robot account may read`() {
        val note = withHarborAnswering(200, "[]") { harbor -> harbor.checkReachable() }

        assertThat(note).isNull()
        assertThat(requests.single().rawPath).isEqualTo("/api/v2.0/projects/team/repositories")
        assertThat(requests.single().rawQuery).isEqualTo("page_size=1")
    }

    @Test
    fun `refused credentials are down`() {
        val failure = assertThrows<IllegalStateException> {
            withHarborAnswering(401, """{"errors":[{"code":"UNAUTHORIZED"}]}""") { harbor -> harbor.checkReachable() }
        }

        assertThat(failure.message).startsWith("Harbor refused the credentials (HTTP 401)")
    }

    @Test
    fun `an account that may not list the repositories is up with a note`() {
        val note = withHarborAnswering(403, """{"errors":[{"code":"FORBIDDEN"}]}""") { harbor -> harbor.checkReachable() }

        assertThat(note).contains("may not list the repositories of project team")
    }

    @Test
    fun `a project that does not exist is down`() {
        val failure = assertThrows<IllegalStateException> {
            withHarborAnswering(404, """{"errors":[{"code":"NOT_FOUND"}]}""") { harbor -> harbor.checkReachable() }
        }

        assertThat(failure.message).contains("no project team")
    }

    @Test
    fun `a slash inside the repository name reaches Harbor escaped twice, with or without the project in front`() {
        withHarborAnswering(200, SCANNED_AND_UNSCANNED_ARTIFACTS) { harbor ->
            harbor.artifacts("payments/api", tagContaining = null, limit = 10)
            harbor.artifacts("team/payments/api", tagContaining = null, limit = 10)
        }

        assertThat(requests.map { it.rawPath })
            .containsOnly("/api/v2.0/projects/team/repositories/payments%252Fapi/artifacts")
    }

    @Test
    fun `versions are asked for newest first with their tags and scan, and filtered by a part of a tag`() {
        withHarborAnswering(200, SCANNED_AND_UNSCANNED_ARTIFACTS) { harbor -> harbor.artifacts("billing", tagContaining = "develop", limit = 5) }

        assertThat(requests.single().rawQuery)
            .isEqualTo("page_size=5&with_tag=true&with_scan_overview=true&sort=-push_time&q=tags%3D%7Edevelop")
    }

    @Test
    fun `an artifact is summarised to its tags, times, size and vulnerabilities by severity`() {
        val artifacts = withHarborAnswering(200, SCANNED_AND_UNSCANNED_ARTIFACTS) { harbor ->
            harbor.artifacts("payments/api", tagContaining = null, limit = 10)
        }

        val scanned = artifacts[0]
        assertThat(scanned.digest).isEqualTo("sha256:1f2e3d4c")
        assertThat(scanned.tags).containsExactly("1.4.2", "latest")
        assertThat(scanned.pushed).isEqualTo("2026-09-27T00:12:30.724Z")
        assertThat(scanned.lastPulled).isEqualTo("2026-09-28T07:01:02.000Z")
        assertThat(scanned.sizeBytes).isEqualTo(312_000_000)
        assertThat(scanned.scan?.status).isEqualTo("Success")
        assertThat(scanned.scan?.severity).isEqualTo("High")
        assertThat(scanned.scan?.total).isEqualTo(42)
        assertThat(scanned.scan?.bySeverity).containsExactlyInAnyOrderEntriesOf(mapOf("Critical" to 1, "High" to 5, "Medium" to 36))

        val unscanned = artifacts[1]
        assertThat(unscanned.tags).isEmpty()
        assertThat(unscanned.lastPulled).isNull()
        assertThat(unscanned.scan).isNull()
    }

    @Test
    fun `a repository without versions answers an empty list`() {
        val artifacts = withHarborAnswering(200, "[]", repositoriesBody = """[{"name": "team/payments/api"}]""") { harbor ->
            harbor.artifacts("payments/api", tagContaining = null, limit = 10)
        }

        assertThat(artifacts).isEmpty()
        assertThat(requests.last().rawQuery).isEqualTo("q=name%3Dteam%2Fpayments%2Fapi&page_size=1")
    }

    /** Harbor answers an unknown repository with an empty list as well, so the tool asks whether it exists. */
    @Test
    fun `an unknown repository is refused with a pointer to the list of repositories`() {
        val failure = assertThrows<IllegalArgumentException> {
            withHarborAnswering(200, "[]") { harbor -> harbor.artifacts("payments/apl", tagContaining = null, limit = 10) }
        }

        assertThat(failure.message).startsWith("Project team has no repository payments/apl.")
    }

    @Test
    fun `a repository must be named`() {
        withHarborAnswering(200, "[]") { harbor ->
            assertThrows<IllegalArgumentException> { harbor.artifacts("team/", tagContaining = null, limit = 10) }
        }
    }

    /** [repositoriesBody], when given, answers the list of repositories instead of [body]. */
    private fun <T> withHarborAnswering(
        status: Int,
        body: String,
        repositoriesBody: String? = null,
        check: (HarborInstance) -> T,
    ): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            synchronized(requests) {
                requests += exchange.requestURI
                authorizations += exchange.requestHeaders.getFirst("Authorization")
            }
            val answer = repositoriesBody?.takeIf { exchange.requestURI.rawPath.endsWith("/repositories") } ?: body
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val settings = HubProperties(
                environments = mapOf(
                    INSTANCE_NAME to EnvironmentProperties(
                        description = "Test registry",
                        type = EnvironmentType.HARBOR,
                        url = "http://127.0.0.1:${server.address.port}",
                    ),
                ),
            ).resolveSettings().getValue(INSTANCE_NAME)
            return check(
                HarborInstance(
                    settings = settings,
                    baseUrl = "http://127.0.0.1:${server.address.port}/",
                    username = ROBOT,
                    token = TOKEN,
                    project = "team",
                    httpClient = HttpClient.newHttpClient(),
                ),
            )
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        const val INSTANCE_NAME = "HARBOR_TEST"
        const val ROBOT = "robot\$team+test"
        const val TOKEN = "test-token"

        /** Trimmed from Harbor's answer: extra_attrs, references and the like are left out. */
        val SCANNED_AND_UNSCANNED_ARTIFACTS = """
            [
              {
                "digest": "sha256:1f2e3d4c",
                "type": "IMAGE",
                "size": 312000000,
                "push_time": "2026-09-27T00:12:30.724Z",
                "pull_time": "2026-09-28T07:01:02.000Z",
                "tags": [{"name": "1.4.2"}, {"name": "latest"}],
                "scan_overview": {
                  "application/vnd.security.vulnerability.report; version=1.1": {
                    "scan_status": "Success",
                    "severity": "High",
                    "summary": {"total": 42, "fixable": 30, "summary": {"Critical": 1, "High": 5, "Medium": 36}}
                  }
                }
              },
              {
                "digest": "sha256:0a0b0c0d",
                "type": "IMAGE",
                "size": 298000000,
                "push_time": "2026-09-20T10:00:00.000Z",
                "pull_time": "0001-01-01T00:00:00.000Z",
                "tags": null
              }
            ]
        """.trimIndent()
    }
}
