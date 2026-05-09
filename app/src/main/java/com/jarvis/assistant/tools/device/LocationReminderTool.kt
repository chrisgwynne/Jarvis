package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.jarvis.assistant.reminders.LocationReminder
import com.jarvis.assistant.reminders.LocationReminderManager
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * LocationReminderTool — set reminders that trigger when arriving at a named place.
 *
 * Uses Android Geocoder for forward geocoding (place name → lat/lng).
 * Falls back to a "set it in Settings" message if geocoding fails.
 *
 * Supported:
 *   "remind me when I get to Tesco to buy milk"
 *   "set a location reminder for home to take medication"
 *   "list my location reminders"
 *   "remove location reminder [label]"
 */
class LocationReminderTool(private val context: Context) : Tool {

    override val name            = "location_reminder"
    override val description     = "Set reminders that trigger when arriving at a named location"
    override val requiresNetwork = false
    override val requiredPermissions = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    override fun schema() = ToolSchema(
        name        = name,
        description = "Create, list, or remove geofenced reminders that fire when the user arrives at a named place.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "enum" to listOf("add", "list", "remove"),
                    "description" to "What to do"
                ),
                "place"  to mapOf("type" to "string", "description" to "Place name for add (e.g. \"Tesco\", \"home\")"),
                "task"   to mapOf("type" to "string", "description" to "What to be reminded to do on arrival"),
                "query"  to mapOf("type" to "string", "description" to "Substring to match when removing an existing reminder")
            ),
            "required" to listOf("action")
        )
    )

    companion object {
        private const val TAG = "LocationReminderTool"

        private val ADD_PATTERN = Regex(
            """(?:remind\s+me\s+when\s+(?:I\s+)?(?:get|arrive|reach)\s+(?:to|at)\s+|""" +
            """set\s+(?:a\s+)?location\s+reminder\s+(?:for|at)\s+)(.+?)\s+to\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        private val LIST_PATTERN = Regex(
            """list\s+(?:my\s+)?location\s+reminders?""",
            RegexOption.IGNORE_CASE
        )
        private val REMOVE_PATTERN = Regex(
            """(?:remove|cancel|delete)\s+(?:location\s+)?reminder\s+(?:for\s+)?(.+)""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        return when {
            ADD_PATTERN.containsMatchIn(t)    -> ToolInput(t, mapOf("action" to "add"))
            LIST_PATTERN.containsMatchIn(t)   -> ToolInput(t, mapOf("action" to "list"))
            REMOVE_PATTERN.containsMatchIn(t) -> ToolInput(t, mapOf("action" to "remove"))
            else                               -> null
        }
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val t       = input.transcript.trim()
        val manager = LocationReminderManager(context)

        ADD_PATTERN.find(t)?.let { m ->
            val place = m.groupValues[1].trim()
            val task  = m.groupValues[2].trim()
            val label = "At $place: $task"

            val location = geocode(place)
                ?: return ToolResult.Failure(
                    "I couldn't find the location '$place'. Try a well-known place name or an address."
                )

            val reminder = LocationReminder(
                id           = UUID.randomUUID().toString(),
                label        = label,
                lat          = location.first,
                lng          = location.second,
                radiusMeters = 150f
            )
            manager.addReminder(reminder)
            Log.d(TAG, "Added location reminder: $label at ${location.first},${location.second}")
            return ToolResult.Success("Got it. I'll remind you to $task when you arrive at $place.")
        }

        LIST_PATTERN.find(t)?.let {
            val all = manager.getAll()
            if (all.isEmpty()) return ToolResult.Success("You have no location reminders set.")
            val items = all.joinToString(". ") { it.label }
            return ToolResult.Success("Your location reminders: $items.")
        }

        REMOVE_PATTERN.find(t)?.let { m ->
            val query   = m.groupValues[1].trim().lowercase()
            val all     = manager.getAll()
            val target  = all.firstOrNull { it.label.lowercase().contains(query) }
                ?: return ToolResult.Failure("No location reminder matching '$query' found.")
            manager.removeReminder(target.id)
            return ToolResult.Success("Location reminder '${target.label}' removed.")
        }

        return ToolResult.Failure("I didn't understand that location reminder command.")
    }

    // ── Geocoding ─────────────────────────────────────────────────────────────

    private fun geocode(placeName: String): Pair<Double, Double>? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())

            val addresses = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                var result: List<android.location.Address>? = null
                val latch = CountDownLatch(1)
                geocoder.getFromLocationName(placeName, 1) { addrs ->
                    result = addrs
                    latch.countDown()
                }
                latch.await(5, TimeUnit.SECONDS)
                result
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(placeName, 1)
            }

            val addr = addresses?.firstOrNull()
            if (addr != null) Pair(addr.latitude, addr.longitude) else null
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding failed for '$placeName': ${e.message}")
            null
        }
    }
}
