package cz.lubos.mcphub.status

import cz.lubos.mcphub.entra.EntraAccountState
import cz.lubos.mcphub.target.ConnectionProbe
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@RestController
class StatusController(
    private val environmentStatusReporter: EnvironmentStatusReporter,
    private val connectionProbe: ConnectionProbe,
) {

    /** Nothing else is served from the root, so opening the host lands on the status page. */
    @GetMapping("/")
    fun root(): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/status")).build()

    @PostMapping("/status/check")
    fun checkNow(): ResponseEntity<Void> {
        connectionProbe.probeAllInBackground()
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/status")).build()
    }

    @GetMapping("/status.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun statusAsJson(): List<EnvironmentView> = environmentStatusReporter.report()

    /** A page to glance at, refreshing itself so it can be left open on a second screen. */
    @GetMapping("/status", produces = [MediaType.TEXT_HTML_VALUE])
    fun statusAsPage(): String {
        val environments = environmentStatusReporter.report()
        val rows = environments.joinToString("\n") { view ->
            """
            <tr class="${view.state.name.lowercase()}">
              <td>${escape(view.name)}<div class="note">${escape(view.description)}</div></td>
              <td>${view.type}</td>
              <td class="state">${view.state}</td>
              <td>${time(view.since)}</td>
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
              <td>${time(account.signedInSince)}</td>
              <td>${time(account.accessTokenValidUntil)}</td>
              <td>${time(account.lastRefresh)}</td>
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

        // An account waiting for its sign-in keeps its environments down, so it leads the page.
        val banners = entraAccounts.filter { it.state != EntraAccountState.SIGNED_IN }.joinToString("\n") { account ->
            val waiting = environments.count { it.entra?.account == account.account }
            val what = if (account.state == EntraAccountState.SIGN_IN_REQUIRED) "must sign in again" else "is not signed in"
            val path = URLEncoder.encode(account.account, StandardCharsets.UTF_8)
            """
            <div class="banner">Entra account <strong>${escape(account.account)}</strong> $what, so $waiting
              environment${if (waiting == 1) "" else "s"} cannot connect.
              <a class="button" href="/entra/sign-in/$path">Sign in</a></div>
            """.trimIndent()
        }

        // While a round runs the page reloads quickly, so the outcome shows as soon as it is known.
        val probing = connectionProbe.isProbing
        val reloadSeconds = if (probing) 2 else 10
        val probeState = if (probing) {
            """<span class="spinner" aria-hidden="true"></span>Checking environments…"""
        } else {
            """<span class="countdown" style="animation-duration: ${reloadSeconds}s" title="The page reloads every $reloadSeconds s"></span>""" +
                "Checked at ${time(connectionProbe.lastRoundFinishedAt()?.toString())}"
        }

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta http-equiv="refresh" content="$reloadSeconds">
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
                header { display: flex; align-items: center; gap: 1rem; margin: 0 0 1rem; }
                header h1 { margin: 0; }
                .probe { color: #6b6b6b; display: flex; align-items: center; gap: .4rem; }
                .spinner { width: .9em; height: .9em; border: 2px solid #c9c9c9; border-top-color: #555;
                  border-radius: 50%; animation: spin .8s linear infinite; }
                @keyframes spin { to { transform: rotate(360deg); } }
                @property --elapsed { syntax: '<percentage>'; inherits: false; initial-value: 0%; }
                .countdown { width: .9em; height: .9em; border-radius: 50%;
                  background: conic-gradient(#555 var(--elapsed), #d6d6d6 0); animation: countdown linear forwards; }
                @keyframes countdown { to { --elapsed: 100%; } }
                @media (prefers-reduced-motion: reduce) { .spinner { animation-duration: 3s; } }
                h2 { font-size: 1rem; margin: 2rem 0 .5rem; }
                .banner { display: flex; align-items: center; gap: .6rem; margin: 0 0 1rem; padding: .6rem .8rem;
                  border: 1px solid #e6c65c; border-radius: 6px; background: #fff8dc; }
                .banner .button { margin-left: auto; }
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
                  .countdown { background: conic-gradient(#bbb var(--elapsed), #3a3d42 0); }
                  .banner { background: #2f2a17; border-color: #6b5a1e; }
                  .spinner { border-color: #3a3d42; border-top-color: #bbb; }
                }
              </style>
            </head>
            <body>
              <header>
                <h1>MCP Hub</h1>
                <span class="probe">$probeState</span>
                <form method="post" action="/status/check"><button type="submit">Check now</button></form>
              </header>
              $banners
              <table>
                <tr><th>Environment</th><th>Type</th><th>State</th><th>Since</th><th>Access</th><th>Pool</th><th>Last error</th></tr>
                $rows
              </table>
              $entraSection
              <script>
                // The server knows neither the reader's language nor time zone; the browser does.
                for (const element of document.querySelectorAll('time[datetime]')) {
                  element.textContent = new Date(element.dateTime)
                    .toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    /** Readable without script as ISO-8601; the script on the page rewrites it in the reader's own format. */
    private fun time(instant: String?) =
        instant?.let { """<time datetime="${escape(it)}">${escape(it)}</time>""" } ?: "—"

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
