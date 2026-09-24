package cz.lubos.mcphub.tool

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import org.junit.jupiter.api.Test
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.method.tool.ReturnMode
import org.springframework.ai.mcp.annotation.method.tool.SyncMcpToolMethodCallback
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tools that only fail. Whatever they throw has to reach the client as a failed tool call, never
 * as a broken response stream.
 */
open class FailingTools {

    @McpTool(name = "checked_failure", description = "Throws a checked exception.")
    open fun checkedFailure(): String =
        throw SQLException("ERROR: value too long for type character varying(10)")

    @McpTool(name = "silent_failure", description = "Throws an exception carrying no message.")
    open fun silentFailure(): String = throw InterruptedException()
}

class ToolCallAspectTest {

    private val tools = AspectJProxyFactory(FailingTools())
        .apply { addAspect(ToolCallAspect()) }
        .getProxy<FailingTools>()

    @Test
    fun `a checked failure leaves as a runtime exception keeping the original message`() {
        val failure = assertFailsWith<RuntimeException> { tools.checkedFailure() }

        assertEquals("ERROR: value too long for type character varying(10)", failure.message)
        assertTrue(failure.cause is SQLException)
    }

    @Test
    fun `a failure without a message still leaves with one`() {
        val failure = assertFailsWith<RuntimeException> { tools.silentFailure() }

        assertEquals("InterruptedException with no message", failure.message)
    }

    /**
     * The reason the aspect translates at all: without it the MCP layer hands the transport an
     * exception with no message, which it answers with HTTP 500 instead of a tool result.
     */
    @Test
    fun `the mcp layer turns a checked failure into an error result`() {
        val method = FailingTools::class.java.getMethod("checkedFailure")
        val callback = SyncMcpToolMethodCallback(ReturnMode.TEXT, method, tools)

        val result = callback.apply(null, CallToolRequest("checked_failure", emptyMap(), null))

        assertEquals(true, result.isError)
        assertTrue(result.content().toString().contains("value too long"))
    }
}
