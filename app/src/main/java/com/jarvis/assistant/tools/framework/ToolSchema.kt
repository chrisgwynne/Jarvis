package com.jarvis.assistant.tools.framework

/**
 * Provider-agnostic schema for a tool that can be exposed to the LLM via function calling.
 *
 * [name] must match [Tool.name].
 * [description] is shown to the LLM to help it decide when to use the tool.
 * [parameters] is a JSON-Schema-compatible map describing the tool's parameters.
 *
 * Providers translate this to their own wire format:
 *   - OpenAI:    tools[].function.{name, description, parameters}
 *   - Anthropic: tools[].{name, description, input_schema}
 */
data class ToolSchema(
    val name: String,
    val description: String,
    val parameters: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
        "required" to emptyList<String>()
    )
)
