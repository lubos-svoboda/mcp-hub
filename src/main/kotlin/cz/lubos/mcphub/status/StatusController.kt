package cz.lubos.mcphub.status

import cz.lubos.mcphub.entra.EntraAccountState
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
              <td>${if (view.readOnly) "read-only" else "<strong>writable</strong>"}${view.entra?.let { "<div class=\"note\">Entra ${escape(it.account)}</div>" } ?: ""}</td>
              <td>${view.pool?.let { "${it.active} active / ${it.idle} idle / ${it.total} total" } ?: "—"}</td>
              <td class="note">${escape(view.lastError ?: "")}</td>
            </tr>
            """.trimIndent()
        }

        val entraAccounts = environmentStatusReporter.entraAccounts()
        val entraRows = entraAccounts.joinToString("\n") { account ->
            val path = URLEncoder.encode(account.account, StandardCharsets.UTF_8)
            val action = if (account.state == EntraAccountState.SIGNED_IN) {
                """<form method="post" action="/entra/sign-out/$path"><button type="submit">Sign out</button></form>"""
            } else {
                """<a class="button" href="/entra/sign-in/$path">Sign in</a>"""
            }
            """
            <tr class="${account.state.name.lowercase()}">
              <td>${escape(account.account)}</td>
              <td class="state">${account.state}</td>
              <td>${escape(account.user ?: "—")}</td>
              <td>${escape(account.signedInSince ?: "—")}</td>
              <td>${escape(account.accessTokenValidUntil ?: "—")}</td>
              <td>${escape(account.lastRefresh ?: "—")}</td>
              <td class="note">${escape(account.lastError ?: "")}</td>
              <td>$action</td>
            </tr>
            """.trimIndent()
        }
        val entraSection = if (entraAccounts.isEmpty()) {
            ""
        } else {
            """
            <h2>Entra accounts</h2>
            <table>
              <tr><th>Account</th><th>State</th><th>User</th><th>Signed in since</th><th>Token valid until</th><th>Last refresh</th><th>Last error</th><th></th></tr>
              $entraRows
            </table>
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
                h2 { font-size: 1rem; margin: 2rem 0 .5rem; }
                tr.signed_in .state { color: #197d3a; }
                tr.sign_in_required .state { color: #b3261e; }
                tr.signed_out .state { color: #8a6d00; }
                form { margin: 0; }
                .button, button { font: inherit; padding: .2rem .7rem; border: 1px solid #b9b9b9; border-radius: 4px;
                  background: #f4f4f4; color: inherit; text-decoration: none; cursor: pointer; }
                @media (prefers-color-scheme: dark) {
                  body { background: #16171a; color: #e7e7e7; }
                  th, td { border-bottom-color: #2e3033; }
                  th, .note { color: #a0a0a0; }
                  tr.up .state { color: #62c37d; }
                  tr.down .state { color: #ef7a72; }
                  tr.connecting .state { color: #d9b03a; }
                  tr.signed_in .state { color: #62c37d; }
                  tr.sign_in_required .state { color: #ef7a72; }
                  tr.signed_out .state { color: #d9b03a; }
                  .button, button { background: #26282c; border-color: #3a3d42; }
                }
              </style>
            </head>
            <body>
              <h1>MCP Hub</h1>
              <table>
                <tr><th>Environment</th><th>Type</th><th>State</th><th>Since</th><th>Access</th><th>Pool</th><th>Last error</th></tr>
                $rows
              </table>
              $entraSection
            </body>
            </html>
        """.trimIndent()
    }

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
