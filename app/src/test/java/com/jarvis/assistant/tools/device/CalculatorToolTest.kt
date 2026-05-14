package com.jarvis.assistant.tools.device

import com.jarvis.assistant.tools.framework.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CalculatorToolTest — covers the regex matcher, the spoken-operator
 * normaliser and the shunting-yard evaluator.  Pure logic, no Android.
 */
class CalculatorToolTest {

    private val tool = CalculatorTool()

    @Test fun `matcher accepts numeric phrases`() {
        assertNotNull(tool.matches("what's 27 times 41"))
        assertNotNull(tool.matches("calculate 2 plus 2"))
        assertNotNull(tool.matches("100 divided by 4"))
    }

    @Test fun `matcher rejects timer-like phrases`() {
        // Calculator must not steal "set a timer for 5 minutes" etc.
        assertNull(tool.matches("set a timer for 5 minutes"))
        assertNull(tool.matches("set an alarm for 7 am"))
    }

    @Test fun `evaluates basic arithmetic`() = runBlocking {
        val results = mapOf(
            "what is 2 plus 2" to "4",
            "what's 27 times 41" to "1107",
            "100 divided by 4" to "25",
            "10 minus 3" to "7",
        )
        for ((phrase, expected) in results) {
            val input = tool.matches(phrase) ?: error("no match for $phrase")
            val r = tool.execute(input) as ToolResult.Success
            assertTrue(
                "$phrase => '${r.spokenFeedback}' should contain $expected",
                r.spokenFeedback.contains(expected),
            )
        }
    }
}
