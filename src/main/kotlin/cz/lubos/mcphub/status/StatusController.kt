package cz.lubos.mcphub.status

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
class StatusController(private val environmentStatusReporter: EnvironmentStatusReporter) {

    /** Nothing else is served from the root, so opening the host lands on the status page. */
    @GetMapping("/")
    fun root(): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/status")).build()

    @GetMapping("/status.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun statusAsJson(): List<EnvironmentView> = environmentStatusReporter.report()

    /** A page to glance at, refreshing itself so it can be left open on a second screen. */
    @GetMapping("/status", produces = [MediaType.TEXT_HTML_VALUE])
    fun statusAsPage(): String {
        val rows = environmentStatusReporter.report().joinToString("\n") { view ->
            """
            <tr class="${view.state.name.lowercase()}">
              <td>${escape(view.name)}<div class="note">${escape(view.description)}</div></td>
              <td>${view.type}</td>
              <td class="state">${view.state}</td>
              <td>${escape(view.since)}</td>
              <td>${if (view.readOnly) "read-only" else "<strong>writable</strong>"}</td>
              <td>${view.pool?.let { "${it.active} active / ${it.idle} idle / ${it.total} total" } ?: "—"}</td>
              <td class="note">${escape(view.lastError ?: "")}</td>
            </tr>
            """.trimIndent()
        }

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta http-equiv="refresh" content="10">
              <title>MCP Hub status</title>
              <style>
                body { font: 14px/1.5 system-ui, sans-serif; margin: 2rem; color: #1b1b1b; }
                h1 { font-size: 1.2rem; margin: 0 0 1rem; }
                table { border-collapse: collapse; width: 100%; }
                th, td { text-align: left; padding: .4rem .6rem; border-bottom: 1px solid #e3e3e3; vertical-align: top; }
                th { font-weight: 600; color: #555; }
                .state { font-weight: 600; }
                tr.up .state { color: #197d3a; }
                tr.down .state { color: #b3261e; }
                tr.connecting .state { color: #8a6d00; }
                .note { color: #6b6b6b; font-size: .85em; }
                @media (prefers-color-scheme: dark) {
                  body { background: #16171a; color: #e7e7e7; }
                  th, td { border-bottom-color: #2e3033; }
                  th, .note { color: #a0a0a0; }
                  tr.up .state { color: #62c37d; }
                  tr.down .state { color: #ef7a72; }
                  tr.connecting .state { color: #d9b03a; }
                }
              </style>
            </head>
            <body>
              <h1>MCP Hub</h1>
              <table>
                <tr><th>Environment</th><th>Type</th><th>State</th><th>Since</th><th>Access</th><th>Pool</th><th>Last error</th></tr>
                $rows
              </table>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
