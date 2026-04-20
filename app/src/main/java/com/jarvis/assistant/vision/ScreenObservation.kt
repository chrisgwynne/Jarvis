package com.jarvis.assistant.vision

import org.json.JSONObject

/**
 * ScreenObservation — one stored record of "look at this".
 *
 * Lives alongside regular memories in the `memory_entries` Room table as a
 * [com.jarvis.assistant.memory.db.entity.MemoryType.SCREEN_OBSERVATION] row.
 * The [VisionScreenAnalyzer.ScreenAnalysis] JSON is the source of truth;
 * this data class is the decoded view used by [ScreenObservationRetriever].
 */
data class ScreenObservation(
    /** Row id in memory_entries. */
    val id: Long,
    /** Wall-clock ms when the screenshot was captured. */
    val capturedAtMs: Long,
    /** Absolute path to the PNG on disk, or null if the file has been pruned. */
    val screenshotPath: String?,
    /** Package name of the foreground app at capture time, or null. */
    val foregroundPackage: String?,
    /** Decoded analysis — already validated, already non-sensitive (we never persist sensitive screens). */
    val analysis: VisionScreenAnalyzer.ScreenAnalysis
) {
    companion object {
        /**
         * Build the JSON content string persisted in MemoryEntry.content.
         * The shape is a superset of the analyzer JSON so the retriever can
         * reconstruct [ScreenObservation] from a single column read.
         */
        fun toStoredContent(
            analysis: VisionScreenAnalyzer.ScreenAnalysis,
            screenshotPath: String?,
            foregroundPackage: String?,
            capturedAtMs: Long
        ): String {
            val obj = JSONObject()
            obj.put("captured_at_ms", capturedAtMs)
            obj.put("screenshot_path", screenshotPath ?: JSONObject.NULL)
            obj.put("foreground_package", foregroundPackage ?: JSONObject.NULL)
            obj.put("analysis", JSONObject(analysis.rawJson))
            return obj.toString()
        }

        /**
         * Parse the persisted JSON back into a [ScreenObservation].  Returns
         * null if the JSON is malformed (e.g. upgraded schema, corruption),
         * so the retriever can skip the row without crashing.
         */
        fun fromStoredContent(id: Long, content: String, analyzer: VisionScreenAnalyzer): ScreenObservation? {
            return try {
                val obj = JSONObject(content)
                val analysisJson = obj.getJSONObject("analysis")
                val analysis = analyzer.parse(analysisJson.toString())
                ScreenObservation(
                    id                = id,
                    capturedAtMs      = obj.optLong("captured_at_ms", 0L),
                    screenshotPath    = obj.optStringOrNull("screenshot_path"),
                    foregroundPackage = obj.optStringOrNull("foreground_package"),
                    analysis          = analysis
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val v = optString(key)
            return v.ifBlank { null }
        }
    }
}

