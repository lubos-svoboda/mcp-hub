package cz.lubos.mcphub.entra

import cz.lubos.mcphub.database.EnvironmentRegistry
import cz.lubos.mcphub.target.ConnectionProbe
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
class EntraSignInController(
    private val entraSignIn: EntraSignIn,
    private val entraAccountRegistry: EntraAccountRegistry,
    private val environmentRegistry: EnvironmentRegistry,
    private val connectionProbe: ConnectionProbe,
) {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    @GetMapping("/entra/sign-in/{accountName}")
    fun signIn(@PathVariable accountName: String, request: HttpServletRequest): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND).location(entraSignIn.start(accountName, redirectUri(request))).build()

    /**
     * Entra posts the answer to the root: the Azure CLI registration accepts http://localhost with any
     * port but no path. A plain GET of the root stays the redirect to the status page.
     */
    @PostMapping("/", params = ["state"], consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun signInAnswer(
        @RequestParam state: String,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(name = "error_description", required = false) errorDescription: String?,
    ): ResponseEntity<String> =
        try {
            if (code != null) {
                entraSignIn.complete(state, code)
                connectionProbe.probeAllInBackground()
            } else {
                entraSignIn.fail(state, listOfNotNull(error, errorDescription).joinToString(": ").ifEmpty { "no code returned" })
            }
            backToStatusPage()
        } catch (refusal: IllegalArgumentException) {
            refused(refusal)
        } catch (failure: Exception) {
            // The account keeps the reason; the status page shows it next to the account.
            logger.warn("Entra sign-in could not be completed: {}", failure.message)
            backToStatusPage()
        }

    @PostMapping("/entra/sign-out/{accountName}")
    fun signOut(@PathVariable accountName: String): ResponseEntity<String> {
        entraAccountRegistry.requireAccount(accountName).signOut()
        environmentRegistry.evictConnectionsOf(accountName)
        connectionProbe.probeAllInBackground()
        return backToStatusPage()
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun refused(refusal: IllegalArgumentException): ResponseEntity<String> =
        ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(refusal.message)

    private fun backToStatusPage(): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/status")).build()

    /**
     * Always localhost, the address the Azure CLI registration names, and the port the browser used:
     * behind a published container port that may differ from the one the server listens on.
     */
    private fun redirectUri(request: HttpServletRequest): String {
        val port = request.getHeader(HttpHeaders.HOST)?.let { host -> URI("http://$host").port } ?: request.serverPort
        return if (port <= 0 || port == 80) "http://localhost/" else "http://localhost:$port/"
    }
}
