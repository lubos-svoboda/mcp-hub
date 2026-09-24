package cz.lubos.mcphub.web

import cz.lubos.mcphub.config.HubProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

/**
 * The server has no authentication and relies on being reachable from this machine only. A web page
 * open in the browser can still reach it: directly, or through DNS rebinding, where a name the page
 * controls is made to point at 127.0.0.1. Such a request carries a foreign Host or Origin, so both
 * are checked, as the MCP specification asks of a server on localhost.
 */
@Component
class LocalOriginFilter(hubProperties: HubProperties) : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val allowedHosts: Set<String> = hubProperties.allowedHosts.map(String::lowercase).toSet()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val refusal = refusal(request)
        if (refusal == null) {
            chain.doFilter(request, response)
            return
        }
        logger.warn("Refused {} {}: {}", request.method, request.requestURI, refusal)
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.TEXT_PLAIN_VALUE
        response.writer.write("Refused: $refusal. This server answers local clients and its own pages only.")
    }

    private fun refusal(request: HttpServletRequest): String? {
        val host = hostName(request.getHeader(HttpHeaders.HOST))
        if (host !in allowedHosts) {
            return "the request was addressed to host $host"
        }
        val origin = request.getHeader(HttpHeaders.ORIGIN) ?: return null
        if (hostName(origin.substringAfter("://", "")) in allowedHosts) {
            return null
        }
        return if (isEntraAnswer(request, origin)) null else "the request came from $origin"
    }

    /** Entra posts the sign-in answer to the root from its own sign-in page; nothing else may come from there. */
    private fun isEntraAnswer(request: HttpServletRequest, origin: String) =
        origin == ENTRA_ORIGIN && request.method == HttpMethod.POST.name() && request.requestURI == "/"

    private fun hostName(authority: String?): String? {
        if (authority.isNullOrBlank()) {
            return null
        }
        return runCatching { URI("http://$authority").host?.lowercase()?.removeSurrounding("[", "]") }.getOrNull()
    }

    private companion object {
        const val ENTRA_ORIGIN = "https://login.microsoftonline.com"
    }
}
