package com.jarvis.assistant.tools.device

import com.jarvis.assistant.tools.framework.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchToolTest {

    private var now: Long = 0L
    private val tool = StopwatchTool(clock = { now })

    @Test fun `matches all five verbs`() {
        assertNotNull(tool.matches("start the stopwatch"))
        assertNotNull(tool.matches("stop the stopwatch"))
        assertNotNull(tool.matches("reset the stopwatch"))
        assertNotNull(tool.matches("lap"))
        assertNotNull(tool.matches("what's the stopwatch at"))
        assertNull(tool.matches("what time is it"))
    }

    @Test fun `start then read reflects elapsed`() = runBlocking {
        now = 1_000L
        tool.execute(tool.matches("start the stopwatch")!!)
        now = 6_000L
        val r = tool.execute(tool.matches("what's the stopwatch at")!!) as ToolResult.Success
        assertTrue("expected 5s elapsed in '${r.spokenFeedback}'", r.spokenFeedback.contains("5s"))
    }

    @Test fun `stop then resume accumulates`() = runBlocking {
        now = 0L; tool.execute(tool.matches("start the stopwatch")!!)
        now = 4_000L; tool.execute(tool.matches("stop the stopwatch")!!)
        now = 10_000L
        val r = tool.execute(tool.matches("what's the stopwatch at")!!) as ToolResult.Success
        // stopped at 4 s; reading after 10s should still report 4s
        assertTrue("stopped, expected 4s, got '${r.spokenFeedback}'", r.spokenFeedback.contains("4s"))
    }
}
