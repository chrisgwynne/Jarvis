package com.jarvis.assistant.runtime.plan

import com.jarvis.assistant.llm.NetworkClient

/**
 * PlanAdapter — resolves `$name` / `$name.field` placeholders in a
 * [PlannedStep.argsJson] against values captured by earlier steps.
 *
 * The syntax is intentionally minimal:
 *
 *   - `$foo`          — substitutes the raw captured string for `foo`.
 *   - `$foo.bar`      — if the captured value parses as a JSON object with
 *                       a string field `bar`, substitutes that field.
 *   - `$foo.bar.baz`  — nested field access (single level of nesting).
 *
 * Unknown references pass through untouched so a malformed placeholder
 * doesn't silently corrupt a tool argument. The adapter never throws; a
 * partial miss is preferable to a crashed plan.
 */
object PlanAdapter {

    private val PLACEHOLDER = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)(\.[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)?)?""")

    /** Rewrite [argsJson] replacing placeholders with values from [ctx].
     *  Returns the input unchanged when [ctx] has nothing captured. */
    fun resolve(argsJson: String, ctx: PlanContext): String {
        if (!ctx.hasCaptures() || argsJson.isEmpty()) return argsJson
        if (!argsJson.contains('$')) return argsJson
        return PLACEHOLDER.replace(argsJson) { match ->
            val name = match.groupValues[1]
            val path = match.groupValues.getOrNull(2)
                ?.removePrefix(".")
                ?.takeIf { it.isNotEmpty() }
                ?.split('.')
                ?: emptyList()
            val raw = ctx.get(name) ?: return@replace match.value
            if (path.isEmpty()) raw else resolvePath(raw, path) ?: match.value
        }
    }

    private fun resolvePath(rawJson: String, path: List<String>): String? {
        return try {
            val root = NetworkClient.gson.fromJson(rawJson, Map::class.java) ?: return null
            var cursor: Any? = root
            for (segment in path) {
                val asMap = cursor as? Map<*, *> ?: return null
                cursor = asMap[segment] ?: return null
            }
            cursor?.toString()
        } catch (_: Exception) {
            null
        }
    }
}
