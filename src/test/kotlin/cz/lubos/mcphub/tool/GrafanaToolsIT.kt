package cz.lubos.mcphub.tool

import cz.lubos.mcphub.grafana.GrafanaRegistry
import cz.lubos.mcphub.grafana.LokiReader
import cz.lubos.mcphub.support.GrafanaTestInstance
import cz.lubos.mcphub.support.GrafanaTestInstance.ENVIRONMENT_NAME
import cz.lubos.mcphub.support.GrafanaTestInstance.LOG_APP
import cz.lubos.mcphub.support.GrafanaTestInstance.LOG_LINE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

@Tag("integration")
class GrafanaToolsIT {

    @Test
    fun `the instance is reachable with a viewer token that sees the Loki datasource`() {
        val registry = GrafanaRegistry(GrafanaTestInstance.hubProperties())

        val note = assertDoesNotThrow { registry.requireInstance(ENVIRONMENT_NAME).checkReachable() }

        assertThat(note).isNull()
    }

    /** Grafana answers its health endpoint without a token, so only a checked token tells this apart. */
    @Test
    fun `an instance whose token was deleted is down`() {
        val registry = GrafanaRegistry(GrafanaTestInstance.hubProperties(token = "glsa_deleted_token"))

        val failure = assertThrows<IllegalStateException> { registry.requireInstance(ENVIRONMENT_NAME).checkReachable() }

        assertThat(failure.message).contains("refused the token (HTTP 401)")
    }

    @Test
    fun `logs are found through the Loki datasource and narrowed to their lines by jq`() {
        val answer = tools().queryLogs(
            environment = ENVIRONMENT_NAME,
            query = "{app=\"$LOG_APP\"} |= \"42\"",
            from = null,
            to = null,
            limit = null,
            jq = ".data.result[].values[][1]",
        )

        // jq leaves each line as a JSON string, so the diacritics must survive the whole way.
        assertThat(answer).isEqualTo("\"$LOG_LINE\"")
    }

    @Test
    fun `label names and the values of one label come from Loki`() {
        val tools = tools()

        assertThat(tools.listLogLabels(ENVIRONMENT_NAME, label = null)).contains("\"app\"")
        assertThat(tools.listLogLabels(ENVIRONMENT_NAME, label = "app")).contains("\"$LOG_APP\"")
    }

    @Test
    fun `any API endpoint can be read and narrowed by jq`() {
        val answer = tools().grafanaApiRequest(ENVIRONMENT_NAME, "/api/datasources", ".[].type")

        assertThat(answer).isEqualTo("\"loki\"")
    }

    @Test
    fun `a read-only instance refuses a write before sending anything`() {
        val failure = assertThrows<IllegalArgumentException> {
            tools().grafanaApiWrite(ENVIRONMENT_NAME, "POST", "/api/dashboards/db", "{}")
        }

        assertThat(failure.message).contains("read-only")
    }

    /** The token only has the Viewer role, so Grafana itself refuses, and its answer is passed on. */
    @Test
    fun `a write on a writable instance reaches Grafana and its refusal is passed on`() {
        val failure = assertThrows<IllegalStateException> {
            tools(readOnly = false).grafanaApiWrite(
                ENVIRONMENT_NAME,
                "POST",
                "/api/dashboards/db",
                """{"dashboard": {"title": "Hub test"}}""",
            )
        }

        assertThat(failure.message).contains("HTTP 403")
    }

    private fun tools(readOnly: Boolean = true) =
        GrafanaTools(GrafanaRegistry(GrafanaTestInstance.hubProperties(readOnly)), LokiReader())
}
