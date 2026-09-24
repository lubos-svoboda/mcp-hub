package cz.lubos.mcphub.web

import cz.lubos.mcphub.config.HubProperties
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

class LocalOriginFilterTest {

    private val mockMvc = MockMvcBuilders.standaloneSetup(Endpoints())
        .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(LocalOriginFilter(HubProperties()))
        .build()

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

    @Test
    fun `an additional host name can be allowed`() {
        val permissive = MockMvcBuilders.standaloneSetup(Endpoints())
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(
                LocalOriginFilter(HubProperties(allowedHosts = listOf("localhost", "host.docker.internal"))),
            )
            .build()

        permissive.get("/status") { header(HttpHeaders.HOST, "host.docker.internal:8282") }.andExpect { status { isOk() } }
    }

    @RestController
    class Endpoints {
        @PostMapping("/", "/mcp", "/status/check")
        fun post(): ResponseEntity<Void> = ResponseEntity.ok().build()

        @GetMapping("/status")
        fun get(): ResponseEntity<Void> = ResponseEntity.ok().build()
    }
}
