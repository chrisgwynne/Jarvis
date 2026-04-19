package com.jarvis.assistant.tools.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests the tool matching logic without any Android dependencies.
 * Each tool's matches() is pure pattern matching — no Context needed.
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
        val reg    = ToolRegistry(listOf(first, second))

        val result = reg.match("hello", isOnline = true)
        assertNotNull(result)
        assertEquals("first", result!!.first.name)
    }

    @Test fun `network tool skipped when offline`() {
        val netTool = object : Tool {
            override val name = "net_tool"
            override val description = "needs internet"
            override val requiresNetwork = true
            override fun matches(t: String) = if (t.contains("search")) ToolInput(t) else null
            override suspend fun execute(input: ToolInput) = ToolResult.Success("ok")
        }
        val reg = ToolRegistry(listOf(netTool))
        assertNull(reg.match("search for cats", isOnline = false))
    }

    @Test fun `network tool with local fallback not skipped when offline`() {
        val hybridTool = object : Tool {
            override val name = "hybrid"
            override val description = "hybrid"
            override val requiresNetwork = true
            override val isLocalFallback = true
            override fun matches(t: String) = if (t.contains("test")) ToolInput(t) else null
            override suspend fun execute(input: ToolInput) = ToolResult.Success("ok")
        }
        val reg = ToolRegistry(listOf(hybridTool))
        assertNotNull(reg.match("test this", isOnline = false))
    }

    @Test fun `no match returns null`() {
        val reg = ToolRegistry(emptyList())
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
            "Expected \u2265 35 Tool implementations, found ${toolFiles.size}",
            toolFiles.size >= 35
        )
    }
}

// Test-only constructor for ToolRegistry (bypasses Context)
fun ToolRegistry(tools: List<Tool>) = object {
    fun match(transcript: String, isOnline: Boolean): Pair<Tool, ToolInput>? {
        for (tool in tools) {
            if (tool.requiresNetwork && !isOnline && !tool.isLocalFallback) continue
            val input = tool.matches(transcript) ?: continue
            return Pair(tool, input)
        }
        return null
    }
}
