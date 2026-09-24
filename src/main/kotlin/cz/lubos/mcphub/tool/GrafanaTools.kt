package cz.lubos.mcphub.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import cz.lubos.mcphub.grafana.GrafanaInstance
import cz.lubos.mcphub.grafana.GrafanaRegistry
import cz.lubos.mcphub.grafana.LokiReader
import net.thisptr.jackson.jq.BuiltinFunctionLoader
import net.thisptr.jackson.jq.JsonQuery
import net.thisptr.jackson.jq.Scope
import net.thisptr.jackson.jq.Versions
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class GrafanaTools(
    private val grafanaRegistry: GrafanaRegistry,
    private val lokiReader: LokiReader,
) {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val objectMapper = ObjectMapper()

    // Loading the builtin functions is the expensive part, so it happens once; every call then
    // works in a child scope of its own, which is how jackson-jq shares a scope between threads.
    private val jqRootScope: Scope = Scope.newEmptyScope().also { scope ->
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, scope)
    }

    @McpTool(
        name = "query_logs",
        description = "Runs a LogQL query against the Loki datasource of a Grafana instance — the " +
            "first one Grafana lists — and returns Loki's answer as JSON: the matching streams " +
            "with their labels and their lines as [timestamp in nanoseconds, line] pairs, newest " +
            "first. Without a time range the last hour is searched. Select streams by label first " +
            "and filter lines after that, for example {app=\"api\"} |= \"error\"; list_log_labels " +
            "shows which labels exist. The answer is cut at the server's size limit, so keep it " +
            "small with limit, a tighter range or a jq expression such as " +
            ".data.result[].values[][1], which leaves only the lines.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun queryLogs(
        @McpToolParam(required = true, description = GRAFANA_ENVIRONMENT)
        environment: String,
        @McpToolParam(required = true, description = "LogQL query, for example {app=\"api\"} |= \"error\".")
        query: String,
        @McpToolParam(
            required = false,
            description = "Start of the range as an ISO-8601 instant, for example 2026-01-31T08:00:00Z. " +
                "One hour before the end when left out.",
        )
        from: String?,
        @McpToolParam(required = false, description = "End of the range as an ISO-8601 instant. Now when left out.")
        to: String?,
        @McpToolParam(
            required = false,
            description = "Largest number of log lines to return. $DEFAULT_LOG_LINES when left out.",
        )
        limit: Int?,
        @McpToolParam(required = false, description = JQ_EXPRESSION)
        jq: String?,
    ): String {
        val instance = grafanaRegistry.requireInstance(environment)
        val end = to?.let(Instant::parse) ?: Instant.now()
        val start = from?.let(Instant::parse) ?: end.minus(LokiReader.DEFAULT_RANGE)
        val answer = lokiReader.queryRange(instance, query, start, end, limit ?: DEFAULT_LOG_LINES)
        return narrow(instance, answer, jq)
    }

    @McpTool(
        name = "list_log_labels",
        description = "Lists the log label names known to the Loki datasource of a Grafana instance, " +
            "or the values of one label when it is named. The answer is Loki's JSON, with the " +
            "names or values under data. Use it to find out what a LogQL query can select on.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun listLogLabels(
        @McpToolParam(required = true, description = GRAFANA_ENVIRONMENT)
        environment: String,
        @McpToolParam(required = false, description = "Label whose values to list. All label names when left out.")
        label: String?,
    ): String {
        val instance = grafanaRegistry.requireInstance(environment)
        val answer = if (label == null) lokiReader.labelNames(instance) else lokiReader.labelValues(instance, label)
        return narrow(instance, answer, jqExpression = null)
    }

    @McpTool(
        name = "grafana_api_request",
        description = "Sends a GET request to any endpoint of a Grafana instance's HTTP API and " +
            "returns the JSON answer. For example /api/search?query=orders finds dashboards, " +
            "/api/dashboards/uid/<uid> returns a dashboard's full model together with its version, " +
            "and /api/v1/provisioning/alert-rules lists alert rules. It only reads; " +
            "grafana_api_write is the tool for changes. Answers are often large and are cut at the " +
            "server's size limit, so narrow them with a jq expression.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun grafanaApiRequest(
        @McpToolParam(required = true, description = GRAFANA_ENVIRONMENT)
        environment: String,
        @McpToolParam(required = true, description = ENDPOINT)
        endpoint: String,
        @McpToolParam(required = false, description = JQ_EXPRESSION)
        jq: String?,
    ): String {
        val instance = grafanaRegistry.requireInstance(environment)
        return narrow(instance, instance.request("GET", endpoint, null), jq)
    }

    @McpTool(
        name = "grafana_api_write",
        description = "Changes something in a Grafana instance through its HTTP API and returns " +
            "Grafana's answer, for example saving a dashboard with POST /api/dashboards/db. Only " +
            "instances that list_environments reports as not read-only accept it. Saving a " +
            "dashboard replaces it whole: read it with grafana_api_request first, then send the " +
            "complete model together with the version you read, so that a change made meanwhile is " +
            "refused rather than overwritten.",
        annotations = McpTool.McpAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false,
            openWorldHint = true,
        ),
    )
    fun grafanaApiWrite(
        @McpToolParam(required = true, description = GRAFANA_ENVIRONMENT)
        environment: String,
        @McpToolParam(required = true, description = "POST, PUT, PATCH or DELETE.")
        method: String,
        @McpToolParam(required = true, description = ENDPOINT)
        endpoint: String,
        @McpToolParam(required = false, description = "JSON request body, when the endpoint takes one.")
        body: String?,
    ): String {
        val instance = grafanaRegistry.requireInstance(environment)
        if (instance.readOnly) {
            throw IllegalArgumentException(writableInstancesMessage(environment))
        }
        val upperCasedMethod = method.uppercase()
        require(upperCasedMethod in listOf("POST", "PUT", "PATCH", "DELETE")) {
            "Use grafana_api_request for reading; this tool only sends POST, PUT, PATCH or DELETE."
        }

        val answer = instance.request(upperCasedMethod, endpoint, body)
        // The only trace a change leaves behind.
        logger.info("Changed {}: {} {}", instance.name, upperCasedMethod, endpoint)
        return answer
    }

    /** Applies the jq expression when given, and caps whatever is left. */
    private fun narrow(instance: GrafanaInstance, answer: String, jqExpression: String?): String {
        val narrowed = if (jqExpression == null) answer else applyJq(answer, jqExpression)
        val limit = instance.settings.maxResponseCharacters
        if (narrowed.length <= limit) {
            return narrowed
        }
        return narrowed.take(limit) +
            "\n… cut at $limit characters; narrow the answer with a jq expression."
    }

    private fun applyJq(answer: String, jqExpression: String): String {
        val results = mutableListOf<JsonNode>()
        JsonQuery.compile(jqExpression, Versions.JQ_1_6)
            .apply(Scope.newChildScope(jqRootScope), objectMapper.readTree(answer)) { result -> results.add(result) }
        return results.joinToString("\n") { objectMapper.writeValueAsString(it) }
    }

    private fun writableInstancesMessage(requestedName: String): String {
        val writableNames = grafanaRegistry.all().filterNot(GrafanaInstance::readOnly).map { it.name }.sorted()
        if (writableNames.isEmpty()) {
            return "Grafana instance $requestedName is read-only, and so is every other one on this server."
        }
        return "Grafana instance $requestedName is read-only. Writable instances: ${writableNames.joinToString()}."
    }

    private companion object {
        const val DEFAULT_LOG_LINES = 100
        const val ENDPOINT = "Endpoint path starting with a slash, including any query string. It is " +
            "appended to the instance's configured address and cannot lead anywhere else."
    }
}
