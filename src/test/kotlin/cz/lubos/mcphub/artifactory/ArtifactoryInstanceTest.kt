package cz.lubos.mcphub.artifactory

import com.fasterxml.jackson.databind.ObjectMapper
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

/**
 * Artifactory itself cannot run in a test, so a local server stands in for it. The answers are
 * trimmed from those of artifactory.cetin, against which the AQL queries were checked by hand.
 */
class ArtifactoryInstanceTest {

    private val requests = mutableListOf<RecordedRequest>()

    @Test
    fun `the token is sent as a bearer token`() {
        withArtifactoryAnswering(200, """[{"key": "crisp"}]""") { artifactory -> artifactory.checkReachable() }

        assertThat(requests.single().authorization).isEqualTo("Bearer $TOKEN")
    }

    @Test
    fun `the probe lists the repositories, which needs the token`() {
        val note = withArtifactoryAnswering(200, """[{"key": "crisp"}]""") { artifactory -> artifactory.checkReachable() }

        assertThat(note).isNull()
        assertThat(requests.single().uri.rawPath).isEqualTo("/artifactory/api/repositories")
    }

    @Test
    fun `a refused token is down`() {
        val failure = assertThrows<IllegalStateException> {
            withArtifactoryAnswering(401, """{"errors":[{"status":401,"message":"Props Authentication Token not found"}]}""") { artifactory ->
                artifactory.checkReachable()
            }
        }

        assertThat(failure.message).startsWith("Artifactory refused the token (HTTP 401)")
    }

    @Test
    fun `a token that may read no repository is up with a note`() {
        val note = withArtifactoryAnswering(200, "[]") { artifactory -> artifactory.checkReachable() }

        assertThat(note).isEqualTo("The token may read no repository.")
    }

    @Test
    fun `an HTML page answering the probe is down with a pointer to the url`() {
        val failure = assertThrows<IllegalStateException> {
            withArtifactoryAnswering(200, "<html>Sign in</html>", contentType = "text/html; charset=utf-8") { artifactory ->
                artifactory.checkReachable()
            }
        }

        assertThat(failure.message)
            .startsWith("Artifactory answered /artifactory/api/repositories with text/html; charset=utf-8 instead of JSON")
    }

    @Test
    fun `plain-text answers are accepted, because some endpoints give no JSON`() {
        val answer = withArtifactoryAnswering(200, "4.1.2", contentType = "text/plain") { artifactory ->
            artifactory.request("/artifactory/api/search/latestVersion?g=org.springframework.boot&a=spring-boot")
        }

        assertThat(answer).isEqualTo("4.1.2")
        assertThat(requests.single().accept).isEqualTo("application/json, */*;q=0.8")
    }

    @Test
    fun `an AQL query is posted as plain text`() {
        withArtifactoryAnswering(200, NO_RESULTS) { artifactory -> artifactory.aql("""items.find({"repo":"crisp"})""") }

        val request = requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.uri.rawPath).isEqualTo("/artifactory/api/search/aql")
        assertThat(request.contentType).isEqualTo("text/plain; charset=utf-8")
        assertThat(request.body).isEqualTo("""items.find({"repo":"crisp"})""")
    }

    @Test
    fun `the tags query finds the manifests in the tag folders of the image, newest first`() {
        val query = withArtifactoryAnswering(200, NO_RESULTS) { artifactory ->
            artifactory.dockerTagsQuery("crisp", "crisp/backend", tagContaining = "rc", limit = 5)
        }

        assertThat(query).isEqualTo(
            """items.find({"repo":"crisp","${'$'}or":[{"name":"manifest.json"},{"name":"list.manifest.json"}],""" +
                """"${'$'}and":[{"path":{"${'$'}match":"crisp/backend/*rc*"}},{"path":{"${'$'}nmatch":"crisp/backend/*/*"}},""" +
                """{"path":{"${'$'}nmatch":"crisp/backend/sha256__*"}}]})""" +
                """.include("repo","path","name","created","sha256").sort({"${'$'}desc":["created"]}).limit(5)""",
        )
    }

    @Test
    fun `a quote in the image name cannot change the query`() {
        val query = withArtifactoryAnswering(200, NO_RESULTS) { artifactory ->
            artifactory.dockerTagsQuery("crisp", "crisp\"}),x", tagContaining = null, limit = 5)
        }

        val criteria = query.removePrefix("items.find(").substringBefore("}).include(") + "}"
        assertThat(ObjectMapper().readTree(criteria).path("\$and").path(0).path("path").path("\$match").asText())
            .isEqualTo("crisp\"}),x/*")
    }

    @Test
    fun `a manifest is reported as its tag, push time and digest`() {
        val tags = withArtifactoryAnswering(200, TWO_TAGS) { artifactory ->
            artifactory.dockerTags("crisp", "/crisp/backend/", tagContaining = null, limit = 10)
        }

        assertThat(tags).containsExactly(
            DockerTag(tag = "26.23.0", created = "2026-11-18T15:23:57.235Z", digest = "sha256:45a6d51768b6b270"),
            DockerTag(tag = "26.22.0-rc1", created = "2026-11-12T09:01:00.000Z", digest = "sha256:0f2d5c38dd7a4f4f"),
        )
    }

    @Test
    fun `no tag matching a filter is an empty answer`() {
        val tags = withArtifactoryAnswering(200, NO_RESULTS) { artifactory ->
            artifactory.dockerTags("crisp", "crisp/backend", tagContaining = "nothing", limit = 10)
        }

        assertThat(tags).isEmpty()
    }

    @Test
    fun `an empty tag filter is no filter, so an unknown image is still refused`() {
        assertThrows<IllegalArgumentException> {
            withArtifactoryAnswering(200, NO_RESULTS) { artifactory ->
                artifactory.dockerTags("crisp", "crisp/backnd", tagContaining = " ", limit = 10)
            }
        }

        assertThat(requests.single().body).contains("\"crisp/backnd/*\"")
    }

    @Test
    fun `an image without any tag is refused with a pointer to the list of images`() {
        val failure = assertThrows<IllegalArgumentException> {
            withArtifactoryAnswering(200, NO_RESULTS) { artifactory ->
                artifactory.dockerTags("docker-hub", "library/alpine", tagContaining = null, limit = 10)
            }
        }

        assertThat(failure.message)
            .startsWith("Repository docker-hub has no image library/alpine")
            .contains("docker-hub-cache", "/artifactory/api/docker/docker-hub/v2/_catalog")
    }

    @Test
    fun `maven versions are asked for by group, artifact and repository`() {
        val versions = withArtifactoryAnswering(200, MAVEN_VERSIONS) { artifactory ->
            artifactory.mavenVersions("org.springframework.boot", "spring-boot", "crisp-maven-virtual")
        }

        assertThat(requests.single().uri.rawPath).isEqualTo("/artifactory/api/search/versions")
        assertThat(requests.single().uri.rawQuery).isEqualTo("g=org.springframework.boot&a=spring-boot&repos=crisp-maven-virtual")
        assertThat(versions).containsExactly(MavenVersion("4.1.2", integration = false), MavenVersion("4.2.0-SNAPSHOT", integration = true))
    }

    @Test
    fun `an empty maven repository searches every repository`() {
        withArtifactoryAnswering(200, MAVEN_VERSIONS) { artifactory ->
            artifactory.mavenVersions("org.springframework.boot", "spring-boot", "")
        }

        assertThat(requests.single().uri.rawQuery).isEqualTo("g=org.springframework.boot&a=spring-boot")
    }

    @Test
    fun `no maven version is refused with Artifactory's own reason`() {
        val failure = assertThrows<IllegalArgumentException> {
            withArtifactoryAnswering(404, """{"errors":[{"status":404,"message":"Repository nope does not exist"}]}""") { artifactory ->
                artifactory.mavenVersions("org.springframework.boot", "spring-boot", "nope")
            }
        }

        assertThat(failure.message).startsWith("No version of org.springframework.boot:spring-boot found: Repository nope does not exist.")
    }

    private data class RecordedRequest(
        val method: String,
        val uri: URI,
        val authorization: String?,
        val accept: String?,
        val contentType: String?,
        val body: String,
    )

    private fun <T> withArtifactoryAnswering(
        status: Int,
        body: String,
        contentType: String = "application/json",
        check: (ArtifactoryInstance) -> T,
    ): T {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            synchronized(requests) {
                requests += RecordedRequest(
                    method = exchange.requestMethod,
                    uri = exchange.requestURI,
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    accept = exchange.requestHeaders.getFirst("Accept"),
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                    body = exchange.requestBody.readAllBytes().decodeToString(),
                )
            }
            exchange.responseHeaders.add("Content-Type", contentType)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val settings = HubProperties(
                environments = mapOf(
                    INSTANCE_NAME to EnvironmentProperties(
                        description = "Test Artifactory",
                        type = EnvironmentType.ARTIFACTORY,
                        url = "http://127.0.0.1:${server.address.port}",
                    ),
                ),
            ).resolveSettings().getValue(INSTANCE_NAME)
            return check(
                ArtifactoryInstance(
                    settings = settings,
                    baseUrl = "http://127.0.0.1:${server.address.port}/",
                    token = TOKEN,
                    httpClient = HttpClient.newHttpClient(),
                ),
            )
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        const val INSTANCE_NAME = "ARTIFACTORY_TEST"
        const val TOKEN = "test-token"

        const val NO_RESULTS = """{"results": [], "range": {"start_pos": 0, "end_pos": 0, "total": 0}}"""

        val TWO_TAGS = """
            {
              "results": [
                {"repo": "crisp", "path": "crisp/backend/26.23.0", "name": "manifest.json",
                 "created": "2026-11-18T15:23:57.235Z", "sha256": "45a6d51768b6b270"},
                {"repo": "crisp", "path": "crisp/backend/26.22.0-rc1", "name": "list.manifest.json",
                 "created": "2026-11-12T09:01:00.000Z", "sha256": "0f2d5c38dd7a4f4f"}
              ],
              "range": {"start_pos": 0, "end_pos": 2, "total": 2, "limit": 10}
            }
        """.trimIndent()

        val MAVEN_VERSIONS = """
            {"results": [{"version": "4.1.2", "integration": false}, {"version": "4.2.0-SNAPSHOT", "integration": true}]}
        """.trimIndent()
    }
}
