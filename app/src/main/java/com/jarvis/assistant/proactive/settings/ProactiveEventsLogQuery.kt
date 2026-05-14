package com.jarvis.assistant.proactive.settings

import com.jarvis.assistant.core.telemetry.DecisionTraceEntity
import com.jarvis.assistant.core.telemetry.DecisionTraceStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * ProactiveEventsLogQuery — adapter that turns the row-level
 * [DecisionTraceStore] into a UI-shaped list of recent proactive
 * decisions ("show last 10 proactive events" in the Proactivity settings
 * diagnostics).
 *
 * Pure parsing — no I/O of its own, no state.  Lives in the proactive/
 * settings package because the UI shape it produces is owned by the
 * Proactivity feature, not by the generic telemetry layer.
 */
class ProactiveEventsLogQuery(private val store: DecisionTraceStore) {

    /**
     * UI-friendly summary of a single trace row.  All fields are nullable
     * because legacy rows (before the schema stabilised) may not have all
     * the JSON keys.
     */
    data class Entry(
        val createdAtMs: Long,
        val eventType: String?,
        val finalScore: Float?,
        /** "speak" | "passive" | "no_events" | "suppressed:<reason>". */
        val outcome: String,
        val suppressionReason: String?,
    ) {
        /** Human-friendly decision word: spoke / notified / suppressed / silent. */
        val decisionLabel: String get() = when {
            outcome == "speak"           -> "spoke"
            outcome == "passive"         -> "notified"
            outcome.startsWith("suppressed") -> "suppressed"
            outcome == "no_events"       -> "silent"
            else                         -> outcome
        }
    }

    /**
     * Fetch the most recent [limit] decision traces, parsed into [Entry].
     * Defaults to 10 to match the Proactivity diagnostics requirement.
     */
    suspend fun recent(limit: Int = 10): List<Entry> =
        store.latest(limit).map { fromTrace(it) }

    private fun fromTrace(row: DecisionTraceEntity): Entry {
        val (eventType, finalScore) = parseTopCandidate(row.candidatesJson)
        val reason = if (row.outcome.startsWith("suppressed")) {
            row.outcome.removePrefix("suppressed").trimStart(':', ' ').ifBlank { null }
        } else null
        return Entry(
            createdAtMs       = row.createdAtMs,
            eventType         = eventType,
            finalScore        = finalScore,
            outcome           = row.outcome,
            suppressionReason = reason,
        )
    }

    /**
     * Pick the top candidate's event type + final score out of a
     * candidatesJson blob.  The engine writes an array of objects with at
     * least `{ type, finalScore }` per candidate; we take the first
     * (highest-scoring) entry.  Returns nulls on any parse failure.
     */
    private fun parseTopCandidate(json: String): Pair<String?, Float?> {
        if (json.isBlank()) return null to null
        return runCatching {
            val arr = JSONArray(json)
            if (arr.length() == 0) return null to null
            val top = arr.optJSONObject(0) ?: return null to null
            val t = top.optString("type", "").ifBlank { null }
            val s = if (top.has("finalScore")) top.optDouble("finalScore", -1.0)
                .takeIf { it >= 0 }?.toFloat() else null
            t to s
        }.getOrElse {
            // Older traces may have stored a non-array object — try that.
            runCatching {
                val obj = JSONObject(json)
                val t = obj.optString("type", "").ifBlank { null }
                val s = if (obj.has("finalScore")) obj.optDouble("finalScore", -1.0)
                    .takeIf { it >= 0 }?.toFloat() else null
                t to s
            }.getOrDefault(null to null)
        }
    }
}
