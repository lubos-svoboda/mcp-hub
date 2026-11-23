package cz.lubos.mcphub.tool

import com.fasterxml.jackson.databind.ObjectMapper
import cz.lubos.mcphub.artifactory.ArtifactoryRegistry
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class ArtifactoryTools(private val artifactoryRegistry: ArtifactoryRegistry) {

    private val objectMapper = ObjectMapper()

    @McpTool(
        name = "list_artifactory_docker_tags",
        description = "Lists the tags of one Docker or OCI image in an Artifactory repository, newest first: " +
            "for each the tag, when Artifactory stored it (created) and its digest. The image is named within " +
            "the repository, for example crisp/backend in repository crisp, which docker pull reaches as " +
            "<host>/crisp/crisp/backend. In a local repository created is when the tag was pushed. A remote " +
            "repository is searched as its cache, for example docker-hub-cache; it has only the tags somebody " +
            "already pulled through Artifactory, and created is that first pull, not the release. " +
            "artifactory_api_request with /artifactory/api/docker/<repository>/v2/_catalog lists the images. " +
            "Use it to find out which tags an image has, which is the newest or which digest a tag points to.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun listArtifactoryDockerTags(
        @McpToolParam(required = true, description = ARTIFACTORY_ENVIRONMENT)
        environment: String,
        @McpToolParam(required = true, description = "Repository key, for example crisp.")
        repository: String,
        @McpToolParam(required = true, description = "Image within the repository, for example crisp/backend.")
        image: String,
        @McpToolParam(required = false, description = "Keeps only the tags containing this text, for example rc.")
        tag: String?,
        @McpToolParam(
            required = false,
            description = "Largest number of tags to return, at most $MAX_TAGS. $DEFAULT_TAGS when left out.",
        )
        limit: Int?,
    ): String {
        val instance = artifactoryRegistry.requireInstance(environment)
        val tagLimit = limit ?: DEFAULT_TAGS
        require(tagLimit in 1..MAX_TAGS) { "The limit must be between 1 and $MAX_TAGS." }
        val answer = objectMapper.writeValueAsString(
            mapOf(
                "repository" to repository,
                "image" to image,
                "tags" to instance.dockerTags(repository, image, tag, tagLimit),
            ),
        )
        return JsonNarrowing.narrow(answer, jqExpression = null, instance.settings.maxResponseCharacters)
    }

    @McpTool(
        name = "list_artifactory_maven_versions",
        description = "Lists the versions of one Maven artifact Artifactory has, for each the version and " +
            "whether it is a snapshot (integration). Without a repository every repository the token may read " +
            "is searched. A remote repository, such as Maven Central behind a virtual one, knows only the " +
            "versions somebody already downloaded through Artifactory, so the list need not be complete.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun listArtifactoryMavenVersions(
        @McpToolParam(required = true, description = ARTIFACTORY_ENVIRONMENT)
        environment: String,
        @McpToolParam(required = true, description = "Group id, for example org.springframework.boot.")
        groupId: String,
        @McpToolParam(required = true, description = "Artifact id, for example spring-boot.")
        artifactId: String,
        @McpToolParam(required = false, description = "Repository key to search in only, for example crisp-maven-virtual.")
        repository: String?,
    ): String {
        val instance = artifactoryRegistry.requireInstance(environment)
        val answer = objectMapper.writeValueAsString(
            mapOf(
                "groupId" to groupId,
                "artifactId" to artifactId,
                "versions" to instance.mavenVersions(groupId, artifactId, repository),
            ),
        )
        return JsonNarrowing.narrow(answer, jqExpression = null, instance.settings.maxResponseCharacters)
    }

    @McpTool(
        name = "artifactory_aql_search",
        description = "Runs an Artifactory Query Language (AQL) query and returns the JSON answer, for example " +
            "items.find({\"repo\":\"crisp\",\"path\":{\"\$match\":\"crisp/backend/*\"},\"name\":\"manifest.json\"})" +
            ".include(\"repo\",\"path\",\"name\",\"created\").sort({\"\$desc\":[\"created\"]}).limit(10). " +
            "Unless the token belongs to an administrator, an items query must name repo, path and name in " +
            "find and include repo, path and name, otherwise Artifactory refuses it with HTTP 400. A remote " +
            "repository is searched as its cache, for example docker-hub-cache. AQL only reads. Answers are " +
            "cut at the server's size limit, so use .limit() and narrow them with a jq expression.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun artifactoryAqlSearch(
        @McpToolParam(required = true, description = ARTIFACTORY_ENVIRONMENT)
        environment: String,
        @McpToolParam(required = true, description = "AQL query, for example items.find({...}).include(...).limit(10).")
        query: String,
        @McpToolParam(required = false, description = JQ_EXPRESSION)
        jq: String?,
    ): String {
        val instance = artifactoryRegistry.requireInstance(environment)
        return JsonNarrowing.narrow(instance.aql(query), jq, instance.settings.maxResponseCharacters)
    }

    @McpTool(
        name = "artifactory_api_request",
        description = "Sends a GET request to any endpoint of an Artifactory REST API and returns the answer. " +
            "For example /artifactory/api/repositories lists the repositories the token may read, " +
            "/artifactory/api/storage/<repository>/<path> describes a file or folder and " +
            "/artifactory/api/docker/<repository>/v2/_catalog lists the images of a Docker repository. " +
            "list_artifactory_docker_tags and list_artifactory_maven_versions return much smaller answers for " +
            "tags and versions. It only reads. Answers are cut at the server's size limit, so narrow them with " +
            "a jq expression.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun artifactoryApiRequest(
        @McpToolParam(required = true, description = ARTIFACTORY_ENVIRONMENT)
        environment: String,
        @McpToolParam(
            required = true,
            description = "Endpoint path starting with a slash, including any query string. It is appended to " +
                "the configured address and cannot lead anywhere else.",
        )
        endpoint: String,
        @McpToolParam(required = false, description = JQ_EXPRESSION)
        jq: String?,
    ): String {
        val instance = artifactoryRegistry.requireInstance(environment)
        return JsonNarrowing.narrow(instance.request(endpoint), jq, instance.settings.maxResponseCharacters)
    }

    private companion object {
        const val DEFAULT_TAGS = 20
        const val MAX_TAGS = 200
    }
}
