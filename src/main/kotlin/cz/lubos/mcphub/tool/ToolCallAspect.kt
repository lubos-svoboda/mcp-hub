package cz.lubos.mcphub.tool

import cz.lubos.mcphub.query.WriteResult
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Wraps every tool call. It logs both ends, because the container log is the only place this
 * server is watched from, and calls from several sessions interleave, so each one carries a
 * number that ties its start to its end. It also makes sure a failure leaves in a shape the
 * protocol can report back.
 */
@Aspect
@Component
class ToolCallAspect {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private val callCounter = AtomicLong()

    @Around("@annotation(org.springframework.ai.mcp.annotation.McpTool)")
    fun aroundToolCall(joinPoint: ProceedingJoinPoint): Any? {
        val call = callCounter.incrementAndGet()
        val toolName = toolName(joinPoint)
        val startedAt = System.nanoTime()

        logger.info("#{} → {}({})", call, toolName, describeArguments(joinPoint.args))
        try {
            val result = joinPoint.proceed()
            logger.info("#{} ← {} ok in {} ms, {}", call, toolName, elapsed(startedAt), describeResult(result))
            return result
        } catch (failure: Throwable) {
            // The message alone: the stack trace of an expected refusal is noise, and the tool
            // answer carries the detail anyway.
            logger.warn(
                "#{} ✗ {} failed in {} ms: {}",
                call,
                toolName,
                elapsed(startedAt),
                failure.message?.lineSequence()?.firstOrNull() ?: failure.javaClass.simpleName,
            )
            throw reportable(failure)
        }
    }

    /**
     * A RuntimeException reaches the caller as a failed tool call it can read. Anything checked is
     * wrapped by the MCP layer in an UndeclaredThrowableException that carries no message, and the
     * transport then answers HTTP 500 and tears down the session instead of reporting the failure.
     * Every failure therefore leaves here as a RuntimeException that has a message.
     */
    private fun reportable(failure: Throwable): Throwable = when {
        failure is Error -> failure
        failure is RuntimeException && !failure.message.isNullOrBlank() -> failure
        else -> IllegalStateException(describeFailure(failure), failure)
    }

    private fun describeFailure(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: "${failure.javaClass.simpleName} with no message"

    private fun toolName(joinPoint: ProceedingJoinPoint): String {
        val method = (joinPoint.signature as MethodSignature).method
        return method.getAnnotation(McpTool::class.java)?.name ?: method.name
    }

    private fun elapsed(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

    private fun describeArguments(arguments: Array<Any?>): String =
        arguments.filterNotNull()
            .joinToString(", ") { argument -> shorten(argument.toString(), ARGUMENT_CHARACTERS) }
            .let { shorten(it, LINE_CHARACTERS) }

    private fun describeResult(result: Any?): String = when (result) {
        null -> "no answer"
        is QueryResponse -> "${result.rowCount} row(s)"
        is WriteResult -> "${result.affectedRows ?: 0} row(s) affected"
        is Collection<*> -> "${result.size} item(s)"
        is String -> "${result.length} characters"
        else -> result.javaClass.simpleName
    }

    /** Whitespace is collapsed so a multi-line statement stays on one log line. */
    private fun shorten(value: String, limit: Int): String {
        val singleLine = value.replace(WHITESPACE, " ").trim()
        return if (singleLine.length <= limit) singleLine else singleLine.take(limit) + "…"
    }

    private companion object {
        const val ARGUMENT_CHARACTERS = 160
        const val LINE_CHARACTERS = 400
        val WHITESPACE = Regex("\\s+")
    }
}
