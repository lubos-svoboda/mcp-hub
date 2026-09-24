package cz.lubos.mcphub.web

import cz.lubos.mcphub.config.EntraAccountProperties
import cz.lubos.mcphub.config.HubProperties
import cz.lubos.mcphub.entra.EntraAccountRegistry
import cz.lubos.mcphub.entra.EntraSignIn
import cz.lubos.mcphub.support.FakeEntraTokenClient
import cz.lubos.mcphub.support.FakeEntraTokenClient.Companion.TENANT_ID
import cz.lubos.mcphub.support.MutableClock
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

class LocalOriginFilterTest {

    private val clock = MutableClock()
    private val hubProperties = HubProperties(entraAccounts = mapOf("WORK" to EntraAccountProperties(tenantId = TENANT_ID)))
    private val entraSignIn = EntraSignIn(EntraAccountRegistry(hubProperties, { FakeEntraTokenClient(clock) }, clock), clock)
    private val mockMvc = mockMvc(hubProperties)

    /** A browser withholding the origin of Entra's page sends the literal `null`. */
    @Test
    fun `an answer with a withheld origin passes only with the state of a sign-in started here`() {
        val state = entraSignIn.start("WORK", "http://localhost:8282/").rawQuery
            .split('&').first { it.startsWith("state=") }.substringAfter('=')

        mockMvc.post("/") { answer(state) }.andExpect { status { isOk() } }
        mockMvc.post("/") { answer("made-up-state") }.andExpect { status { isForbidden() } }
    }

    /** An MCP client such as Claude Code sends no Origin at all. */
    @Test
    fun `a local client without an origin is let through`() {
        mockMvc.post("/mcp") { header(HttpHeaders.HOST, "127.0.0.1:8282") }.andExpect { status { isOk() } }
        mockMvc.post("/mcp") { header(HttpHeaders.HOST, "[::1]:8282") }.andExpect { status { isOk() } }
    }

    @Test
    fun `the server's own pages may post to it`() {
        mockMvc.post("/status/check") {
            header(HttpHeaders.HOST, "localhost:8282")
            header(HttpHeaders.ORIGIN, "http://localhost:8282")
        }.andExpect { status { isOk() } }
    }

    /** DNS rebinding: the browser believes it talks to the attacker's name, which now points at 127.0.0.1. */
    @Test
    fun `a request addressed to a foreign host name is refused`() {
        mockMvc.post("/mcp") { header(HttpHeaders.HOST, "rebind.attacker.example:8282") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `a request from a foreign web page is refused`() {
        mockMvc.post("/mcp") {
            header(HttpHeaders.HOST, "127.0.0.1:8282")
            header(HttpHeaders.ORIGIN, "https://attacker.example")
        }.andExpect {
            status { isForbidden() }
            content { string(org.hamcrest.Matchers.containsString("https://attacker.example")) }
        }
    }

    @Test
    fun `Entra may post its sign-in answer to the root and nowhere else`() {
        mockMvc.post("/") {
            header(HttpHeaders.HOST, "localhost:8282")
            header(HttpHeaders.ORIGIN, "https://login.microsoftonline.com")
        }.andExpect { status { isOk() } }

        mockMvc.post("/mcp") {
            header(HttpHeaders.HOST, "localhost:8282")
            header(HttpHeaders.ORIGIN, "https://login.microsoftonline.com")
        }.andExpect { status { isForbidden() } }
    }

    /** The local names stay allowed; a configured list adds to them rather than replacing them. */
    @Test
    fun `an additional host name is allowed besides the local ones`() {
        val permissive = mockMvc(hubProperties.copy(additionalAllowedHosts = listOf("host.docker.internal")))

        permissive.get("/status") { header(HttpHeaders.HOST, "host.docker.internal:8282") }.andExpect { status { isOk() } }
        permissive.get("/status") { header(HttpHeaders.HOST, "localhost:8282") }.andExpect { status { isOk() } }
    }

    private fun mockMvc(properties: HubProperties) = MockMvcBuilders.standaloneSetup(Endpoints())
        .addFilters<StandaloneMockMvcBuilder>(LocalOriginFilter(properties, entraSignIn))
        .build()

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.answer(state: String) {
        header(HttpHeaders.HOST, "localhost:8282")
        header(HttpHeaders.ORIGIN, "null")
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        param("state", state)
        param("code", "code-123")
    }

    @RestController
    class Endpoints {
        @PostMapping("/", "/mcp", "/status/check")
        fun post(): ResponseEntity<Void> = ResponseEntity.ok().build()

        @GetMapping("/status")
        fun get(): ResponseEntity<Void> = ResponseEntity.ok().build()
    }
}
