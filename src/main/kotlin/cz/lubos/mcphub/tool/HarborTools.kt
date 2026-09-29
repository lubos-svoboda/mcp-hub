package cz.lubos.mcphub.tool

import com.fasterxml.jackson.databind.ObjectMapper
import cz.lubos.mcphub.harbor.HarborRegistry
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class HarborTools(private val harborRegistry: HarborRegistry) {

    private val objectMapper = ObjectMapper()

    @McpTool(
        name = "list_harbor_artifacts",
        description = "Lists the versions (artifacts) of one image repository in a Harbor registry, newest " +
            "first: for each its digest, its tags, when it was pushed and last pulled, its size in bytes and, " +
            "when Harbor scanned it and the account may see the result, the vulnerabilities by severity. " +
            "scan is null otherwise. The repository is named within the registry's project, for example " +
            "payments/api; harbor_api_request with /api/v2.0/projects/<project>/repositories lists " +
            "the repositories. Use it to find out which tags an image has, which version a tag points to " +
            "or how vulnerable a version is.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun listHarborArtifacts(
        @McpToolParam(required = true, description = HARBOR_ENVIRONMENT)
        environment: String,
        @McpToolParam(
            required = true,
            description = "Repository within the registry's project, for example payments/api. The project " +
                "may be written in front of it or left out.",
        )
        repository: String,
        @McpToolParam(required = false, description = "Keeps only the versions with a tag containing this text, for example develop.")
        tag: String?,
        @McpToolParam(
            required = false,
            description = "Largest number of versions to return, at most $MAX_ARTIFACTS. $DEFAULT_ARTIFACTS when left out.",
        )
        limit: Int?,
    ): String {
        val instance = harborRegistry.requireInstance(environment)
        val artifactLimit = limit ?: DEFAULT_ARTIFACTS
        require(artifactLimit in 1..MAX_ARTIFACTS) { "The limit must be between 1 and $MAX_ARTIFACTS." }
        val answer = objectMapper.writeValueAsString(
            mapOf(
                "project" to instance.project,
                "repository" to repository,
                "artifacts" to instance.artifacts(repository, tag, artifactLimit),
            ),
        )
        return JsonNarrowing.narrow(answer, jqExpression = null, instance.settings.maxResponseCharacters)
    }

    @McpTool(
        name = "harbor_api_request",
        description = "Sends a GET request to any endpoint of a Harbor registry's API and returns the JSON " +
            "answer. For example /api/v2.0/projects/<project>/repositories?page_size=100 lists the repositories " +
            "of a project. A slash inside a repository name must be written as %252F in the path, for example " +
            "/api/v2.0/projects/<project>/repositories/payments%252Fapi/artifacts; list_harbor_artifacts does " +
            "that on its own and returns a much smaller answer for the versions of an image. It only reads. " +
            "Answers are cut at the server's size limit, so narrow them with a jq expression.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun harborApiRequest(
        @McpToolParam(required = true, description = HARBOR_ENVIRONMENT)
        environment: String,
        @McpToolParam(
            required = true,
            description = "Endpoint path starting with a slash, including any query string. It is appended to " +
                "the registry's configured address and cannot lead anywhere else.",
        )
        endpoint: String,
        @McpToolParam(required = false, description = JQ_EXPRESSION)
        jq: String?,
    ): String {
        val instance = harborRegistry.requireInstance(environment)
        return JsonNarrowing.narrow(instance.request(endpoint), jq, instance.settings.maxResponseCharacters)
    }

    private companion object {
        const val DEFAULT_ARTIFACTS = 10
        const val MAX_ARTIFACTS = 100
    }
}
