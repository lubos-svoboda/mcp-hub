package cz.lubos.mcphub.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import net.thisptr.jackson.jq.BuiltinFunctionLoader
import net.thisptr.jackson.jq.JsonQuery
import net.thisptr.jackson.jq.Scope
import net.thisptr.jackson.jq.Versions

/** Shared by every tool that returns an HTTP API's JSON answer. */
internal object JsonNarrowing {

    private val objectMapper = ObjectMapper()

    // Loading the builtin functions is the expensive part, so it happens once; every call then
    // works in a child scope of its own, which is how jackson-jq shares a scope between threads.
    private val jqRootScope: Scope = Scope.newEmptyScope().also { scope ->
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, scope)
    }

    /** Applies the jq expression when given, and caps whatever is left. */
    fun narrow(answer: String, jqExpression: String?, maxResponseCharacters: Int): String {
        val narrowed = if (jqExpression == null) answer else applyJq(answer, jqExpression)
        if (narrowed.length <= maxResponseCharacters) {
            return narrowed
        }
        return narrowed.take(maxResponseCharacters) +
            "\n… cut at $maxResponseCharacters characters; narrow the answer with a jq expression."
    }

    private fun applyJq(answer: String, jqExpression: String): String {
        val results = mutableListOf<JsonNode>()
        JsonQuery.compile(jqExpression, Versions.JQ_1_6)
            .apply(Scope.newChildScope(jqRootScope), objectMapper.readTree(answer)) { result -> results.add(result) }
        return results.joinToString("\n") { objectMapper.writeValueAsString(it) }
    }
}
