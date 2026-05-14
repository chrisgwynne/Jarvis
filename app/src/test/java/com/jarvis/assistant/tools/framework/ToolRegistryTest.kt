package com.jarvis.assistant.tools.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests the tool matching logic without any Android dependencies.
 * Each tool's matches() is pure pattern matching — no Context needed.
 *
 * History note: a stale top-level function `fun ToolRegistry(tools)` used to
 * sit at the bottom of this file as a shadowing hack from before
 * [ToolRegistry] had a real constructor.  Under Kotlin 2.2 that helper's
 * return type was inferred as `Any`, hiding `match` from callers.  Tier-A
 * test-suite stabilisation deleted the shadow and pointed the tests at the
 * real (now-public) primary constructor.
 */
class ToolRegistryTest {

    // Minimal stub tool for testing registry ordering
    private fun stubTool(toolName: String, vararg patterns: Regex) = object : Tool {
        override val name = toolName
        override val description = toolName
        override fun matches(transcript: String): ToolInput? =
            if (patterns.any { it.containsMatchIn(transcript) }) ToolInput(transcript) else null
        override suspend fun execute(input: ToolInput): ToolResult = ToolResult.Success("ok")
    }

    @Test fun `first matching tool wins`() {
        val first  = stubTool("first",  Regex("hello"))
        val second = stubTool("second", Regex("hello"))
        val reg: ToolRegistry = ToolRegistry(listOf(first, second))

        val result: Pair<Tool, ToolInput>? = reg.match("hello", isOnline = true)
        assertNotNull(result)
        assertEquals("first", result!!.first.name)
    }

    @Test fun `network tool skipped when offline`() {
        val netTool = object : Tool {
            override val name = "net_tool"
            override val description = "needs internet"
            override val requiresNetwork = true
            override fun matches(t: String): ToolInput? =
                if (t.contains("search")) ToolInput(t) else null
            override suspend fun execute(input: ToolInput) = ToolResult.Success("ok")
        }
        val reg: ToolRegistry = ToolRegistry(listOf<Tool>(netTool))
        assertNull(reg.match("search for cats", isOnline = false))
    }

    @Test fun `network tool with local fallback not skipped when offline`() {
        val hybridTool = object : Tool {
            override val name = "hybrid"
            override val description = "hybrid"
            override val requiresNetwork = true
            override val isLocalFallback = true
            override fun matches(t: String): ToolInput? =
                if (t.contains("test")) ToolInput(t) else null
            override suspend fun execute(input: ToolInput) = ToolResult.Success("ok")
        }
        val reg: ToolRegistry = ToolRegistry(listOf<Tool>(hybridTool))
        assertNotNull(reg.match("test this", isOnline = false))
    }

    @Test fun `no match returns null`() {
        val reg: ToolRegistry = ToolRegistry(emptyList())
        assertNull(reg.match("what is the weather", isOnline = true))
    }

    /**
     * Every class implementing [Tool] must expose a [ToolSchema] so the LLM's
     * function-calling loop can reach it.  Regex-only routing is now the
     * fallback path, not the primary one.
     *
     * Implemented as a source-scan so it runs as a pure unit test: no Android
     * runtime, no Context, no need to instantiate each tool (most tools take
     * constructor dependencies that would require extensive mocking).
     */
    @Test fun `every Tool implementation overrides schema()`() {
        val toolsDir = File("src/main/java/com/jarvis/assistant/tools")
        require(toolsDir.exists()) { "Tools source directory not found: ${toolsDir.absolutePath}" }

        val toolFiles = toolsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter {
                val src = it.readText()
                // Matches both ": Tool {" and ": Tool,\n" (multi-interface)
                Regex("""[:,]\s*Tool\b""").containsMatchIn(src) &&
                    !src.contains("interface Tool")   // skip the Tool interface itself
            }
            .toList()

        val missing = toolFiles.filter { !it.readText().contains("override fun schema()") }

        assertTrue(
            "Tools without override fun schema(): " +
                missing.joinToString { it.nameWithoutExtension },
            missing.isEmpty()
        )
        assertTrue(
            "Expected ≥ 35 Tool implementations, found ${toolFiles.size}",
            toolFiles.size >= 35
        )
    }
}
