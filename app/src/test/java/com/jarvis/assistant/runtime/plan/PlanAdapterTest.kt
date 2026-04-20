package com.jarvis.assistant.runtime.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanAdapterTest {

    // "$" kept outside the string template to avoid Kotlin interpolation confusion.
    private val D = "$"

    @Test
    fun `no captures returns input unchanged`() {
        val ctx = PlanContext()
        val input = "{\"to\":\"${D}contact.phone\"}"
        assertEquals(input, PlanAdapter.resolve(input, ctx))
    }

    @Test
    fun `simple scalar substitution`() {
        val ctx = PlanContext().apply { capture("foo", "bar") }
        val out = PlanAdapter.resolve("{\"k\":\"${D}foo\"}", ctx)
        assertEquals("{\"k\":\"bar\"}", out)
    }

    @Test
    fun `nested field substitution`() {
        val ctx = PlanContext().apply {
            capture("contact", "{\"name\":\"Dan\",\"phone\":\"+1234\"}")
        }
        val out = PlanAdapter.resolve("{\"to\":\"${D}contact.phone\"}", ctx)
        assertEquals("{\"to\":\"+1234\"}", out)
    }

    @Test
    fun `unknown reference passes through`() {
        val ctx = PlanContext().apply { capture("foo", "bar") }
        val input = "{\"k\":\"${D}baz\"}"
        assertEquals(input, PlanAdapter.resolve(input, ctx))
    }

    @Test
    fun `missing nested field leaves placeholder intact`() {
        val ctx = PlanContext().apply { capture("contact", "{\"name\":\"Dan\"}") }
        val input = "{\"t\":\"${D}contact.phone\"}"
        assertEquals(input, PlanAdapter.resolve(input, ctx))
    }

    @Test
    fun `plan context captures and returns values`() {
        val ctx = PlanContext()
        assertFalse(ctx.hasCaptures())
        ctx.capture("x", "y")
        assertEquals("y", ctx.get("x"))
        assertTrue(ctx.hasCaptures())
    }
}
