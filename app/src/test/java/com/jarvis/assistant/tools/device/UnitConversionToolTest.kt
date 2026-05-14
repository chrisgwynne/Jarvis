package com.jarvis.assistant.tools.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UnitConversionToolTest — pure-logic coverage for the regex matcher and
 * the numeric conversion + temperature special case.
 */
class UnitConversionToolTest {

    private val tool = UnitConversionTool()

    @Test fun `miles to km`() {
        val r = tool.convert(10.0, "mile", "kilometre")!!
        assertEquals(16.09344, r, 0.001)
    }

    @Test fun `kg to lb`() {
        val r = tool.convert(5.0, "kilogram", "pound")!!
        assertEquals(11.023, r, 0.01)
    }

    @Test fun `fahrenheit to celsius`() {
        assertEquals(0.0, tool.convert(32.0, "fahrenheit", "celsius")!!, 0.001)
        assertEquals(100.0, tool.convert(212.0, "fahrenheit", "celsius")!!, 0.001)
    }

    @Test fun `celsius to fahrenheit`() {
        assertEquals(32.0, tool.convert(0.0, "celsius", "fahrenheit")!!, 0.001)
    }

    @Test fun `cross-dimension returns null`() {
        assertNull(tool.convert(10.0, "kilogram", "metre"))
    }

    @Test fun `aliases map correctly`() {
        assertEquals("mile", tool.canonical("miles"))
        assertEquals("kilogram", tool.canonical("kg"))
        assertEquals("celsius", tool.canonical("°c"))
    }

    @Test fun `matcher accepts common phrasings`() {
        assertNotNull(tool.matches("convert 12 miles to km"))
        assertNotNull(tool.matches("5 kg to lb"))
        assertNotNull(tool.matches("32 f to c"))
        assertNotNull(tool.matches("100 metres in feet"))
    }

    @Test fun `execute returns spoken result`() = runBlocking {
        val input = tool.matches("10 miles to km")!!
        val r = tool.execute(input)
        assertTrue("expected Success, got $r",
            r is com.jarvis.assistant.tools.framework.ToolResult.Success)
    }
}
