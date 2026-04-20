package com.jarvis.assistant.runtime.plan

/**
 * PlanContext — scratchpad of values produced by earlier [PlannedStep]s so
 * later steps can reference them through `$name` / `$name.field` placeholders
 * in their [PlannedStep.argsJson].
 *
 * Stays in memory for the lifetime of a single plan execution. A plan that
 * doesn't opt in (no step sets [PlannedStep.resultCapture]) never populates
 * the context; plans that do get cheap output threading without every tool
 * learning a new protocol.
 *
 * Values are stored as the raw [com.jarvis.assistant.tools.framework.ToolResult.Success.rawData]
 * string. [PlanAdapter] parses fields lazily when a placeholder is resolved,
 * so the scratchpad remains a dumb store rather than a typed tree.
 */
class PlanContext {

    private val captured: MutableMap<String, String> = linkedMapOf()

    fun capture(name: String, rawData: String) {
        captured[name] = rawData
    }

    fun get(name: String): String? = captured[name]

    fun snapshot(): Map<String, String> = captured.toMap()

    /** True when at least one step captured a value — used by the runner to
     *  short-circuit the adapter for cheap "no-output-passing" plans. */
    fun hasCaptures(): Boolean = captured.isNotEmpty()
}
