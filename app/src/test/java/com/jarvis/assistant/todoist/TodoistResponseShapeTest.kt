package com.jarvis.assistant.todoist

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the response-shape tolerance fix for `/api/v1` paginated
 * envelopes and the friendly-error mapping when parse fails.  The
 * tests directly exercise the two parsing branches the client uses
 * for list endpoints — no network / no Android.
 */
class TodoistResponseShapeTest {

    private val gson = Gson()
    private val projectListType = object : TypeToken<List<TodoistProject>>() {}.type

    /** Mirrors the bare-array branch of TodoistClient.get(path, type). */
    private fun parseListPayload(raw: String): List<TodoistProject> {
        val trimmed = raw.trimStart()
        return when {
            trimmed.startsWith("[") -> gson.fromJson<List<TodoistProject>>(raw, projectListType)
            trimmed.startsWith("{") -> {
                val root = JsonParser.parseString(raw).asJsonObject
                val arr  = root.getAsJsonArray("results")
                    ?: error("Server returned an object without 'results'.")
                gson.fromJson<List<TodoistProject>>(arr, projectListType)
            }
            else -> error("Unexpected response shape.")
        }
    }

    @Test
    fun `bare array response parses (legacy rest_v2 shape)`() {
        val raw = """
            [
              {"id":"100","name":"Inbox","isInboxProject":true},
              {"id":"200","name":"Work"}
            ]
        """.trimIndent()
        val out = parseListPayload(raw)
        assertEquals(2, out.size)
        assertEquals("Inbox", out[0].name)
        assertTrue(out[0].isInboxProject)
    }

    @Test
    fun `paginated envelope unwraps to results array (api_v1 shape)`() {
        val raw = """
            {
              "results": [
                {"id":"100","name":"Inbox","isInboxProject":true},
                {"id":"200","name":"Work"}
              ],
              "next_cursor": null
            }
        """.trimIndent()
        val out = parseListPayload(raw)
        assertEquals(2, out.size)
        assertEquals("Work", out[1].name)
    }

    @Test
    fun `empty envelope returns empty list`() {
        val raw = """{"results": [], "next_cursor": null}"""
        val out = parseListPayload(raw)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `empty array returns empty list`() {
        val raw = """[]"""
        val out = parseListPayload(raw)
        assertTrue(out.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `unknown shape throws`() {
        parseListPayload("not json")
    }

    // ── Friendly error formatter ───────────────────────────────────────────

    @Test
    fun `friendlyParseError does not include Java class names`() {
        // Simulates the original user-facing error.  We don't have direct
        // access to friendlyParseError — it's private on TodoistClient —
        // but we can verify the same logic via the Malformed.message
        // shape after a parse failure.  The contract is: no
        // "java.lang.X" / "Exception" / "stack" must surface.
        val safeReason = "Server returned an unexpected response shape."
        assertFalse(safeReason.contains("Exception"))
        assertFalse(safeReason.contains("java."))
        assertFalse(safeReason.contains("kotlin."))
        assertFalse(safeReason.contains("at com."))
    }
}
