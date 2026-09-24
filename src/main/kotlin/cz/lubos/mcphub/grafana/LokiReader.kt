package cz.lubos.mcphub.grafana

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Loki is reached through Grafana's datasource proxy, so one token and one base URL serve both.
 * The datasource identifier is looked up once per instance and kept, because it does not change
 * while the server runs.
 */
@Component
class LokiReader {

    private val objectMapper = ObjectMapper()
    private val datasourceUidByInstance = ConcurrentHashMap<String, String>()

    fun queryRange(
        instance: GrafanaInstance,
        query: String,
        from: Instant,
        to: Instant,
        limit: Int,
    ): String {
        val parameters = "query=${encode(query)}" +
            "&start=${from.toEpochMilli()}000000" +
            "&end=${to.toEpochMilli()}000000" +
            "&limit=$limit" +
            "&direction=backward"
        return proxyGet(instance, "/loki/api/v1/query_range?$parameters")
    }

    fun labelNames(instance: GrafanaInstance): String = proxyGet(instance, "/loki/api/v1/labels")

    fun labelValues(instance: GrafanaInstance, label: String): String =
        proxyGet(instance, "/loki/api/v1/label/${encode(label)}/values")

    private fun proxyGet(instance: GrafanaInstance, path: String): String =
        instance.request("GET", "/api/datasources/proxy/uid/${datasourceUid(instance)}$path", null)

    private fun datasourceUid(instance: GrafanaInstance): String =
        datasourceUidByInstance.getOrPut(instance.name) {
            val datasources = objectMapper.readTree(instance.request("GET", "/api/datasources", null))
            val loki = datasources.firstOrNull { it.path("type").asText() == "loki" }
                ?: throw IllegalStateException(
                    "No Loki datasource is configured in Grafana instance ${instance.name}.",
                )
            loki.path("uid").asText()
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        val DEFAULT_RANGE: Duration = Duration.ofHours(1)
    }
}
