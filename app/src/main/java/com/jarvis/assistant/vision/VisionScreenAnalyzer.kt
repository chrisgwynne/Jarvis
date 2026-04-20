package com.jarvis.assistant.vision

import android.util.Log
import com.jarvis.assistant.camera.VisionClient
import com.jarvis.assistant.llm.LlmRouter
import com.jarvis.assistant.llm.Message
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * VisionScreenAnalyzer — runs the multimodal model over a captured screenshot
 * and parses the response into a strict [ScreenAnalysis] record.
 *
 * WHY A SEPARATE ANALYZER (rather than reusing AnalyzeCameraViewTool)?
 *   * The on-screen prompt is different: we want structured JSON, not prose.
 *   * We must detect sensitive content (passwords, banking, OTP) and block
 *     storage + redact the spoken reply. AnalyzeCameraViewTool has no such
 *     obligation — the camera view doesn't normally contain credentials.
 *   * The spoken summary must be 1 sentence, not 2 — screens are dense and
 *     a longer summary reads as clutter.
 *
 * WIRE FORMAT:
 *   We reuse [VisionClient.encodeImageForPipeline] to produce base64 JPEG and
 *   send it through [LlmRouter.completeSilent] so the turn never enters the
 *   user-facing conversation history.  Providers that don't support images
 *   (Gemini non-vision, Ollama, Kimi, MiniMax) will return prose instead of
 *   JSON — we treat that as a parse failure and surface an analyze error.
 */
class VisionScreenAnalyzer(
    private val visionClient: VisionClient,
    private val llmRouter: LlmRouter
) {

    companion object {
        private const val TAG = "VisionScreenAnalyzer"

        /**
         * System prompt — hard-coded JSON contract. We ask the model to emit
         * ONLY the JSON object so we can parse without regex heuristics.
         */
        private const val SYSTEM_PROMPT =
            "You are Jarvis's screen-observation analyst. " +
            "Given a screenshot of the user's Android device, emit ONLY a single JSON object " +
            "with this exact schema (no prose, no markdown fences):\n" +
            "{\n" +
            "  \"summary\": string,            // 1 short sentence, what the user is looking at\n" +
            "  \"app_name\": string,           // visible app name or \"unknown\"\n" +
            "  \"screen_type\": string,        // e.g. chat, email, article, settings, lock_screen, login\n" +
            "  \"user_intent\": string,        // best guess at what the user is doing\n" +
            "  \"important_text\": [string],   // up to 5 short strings that matter (titles, amounts, dates)\n" +
            "  \"action_items\": [string],     // up to 3 actionable next steps visible on screen\n" +
            "  \"entities\": {\n" +
            "    \"people\":   [string],\n" +
            "    \"brands\":   [string],\n" +
            "    \"products\": [string],\n" +
            "    \"urls\":     [string],\n" +
            "    \"emails\":   [string]\n" +
            "  },\n" +
            "  \"sensitive\": boolean,         // true if the screen shows a password, card number, OTP, banking, medical, or other credential\n" +
            "  \"confidence\": number          // 0.0–1.0, your confidence in the extraction\n" +
            "}\n" +
            "If you cannot see the screen clearly, return sensible defaults with confidence below 0.5. " +
            "Never invent text that is not visible."

        private const val USER_PROMPT =
            "Analyse this screenshot and return the JSON object described in the system message."
    }

    /**
     * Captured, parsed verdict on one screenshot.  Mirrors the JSON contract
     * so callers never touch raw strings.
     */
    data class ScreenAnalysis(
        val summary:        String,
        val appName:        String,
        val screenType:     String,
        val userIntent:     String,
        val importantText:  List<String>,
        val actionItems:    List<String>,
        val people:         List<String>,
        val brands:         List<String>,
        val products:       List<String>,
        val urls:           List<String>,
        val emails:         List<String>,
        val sensitive:      Boolean,
        val confidence:     Double,
        /** The raw JSON string we got back — stored so the repository can persist it unchanged. */
        val rawJson:        String
    )

    sealed class Result {
        data class Success(val analysis: ScreenAnalysis) : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * Send [pngFile] to the model and parse the JSON reply. Throws nothing —
     * parse / network failures are returned as [Result.Failure].
     */
    suspend fun analyze(pngFile: File): Result {
        val base64 = try {
            visionClient.encodeImageForPipeline(pngFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to encode ${pngFile.name}: ${e.message}")
            return Result.Failure("Could not prepare screenshot for analysis")
        }

        val messages = listOf(
            Message(role = "system", content = SYSTEM_PROMPT),
            Message(role = "user",   content = USER_PROMPT, imageBase64 = base64)
        )

        val raw = llmRouter.completeSilent(messages)
        if (raw.isBlank()) return Result.Failure("Empty model response")

        return try {
            val analysis = parse(raw)
            Log.d(TAG, "Parsed screen analysis: app=${analysis.appName} " +
                       "type=${analysis.screenType} sensitive=${analysis.sensitive} " +
                       "conf=${"%.2f".format(analysis.confidence)}")
            Result.Success(analysis)
        } catch (e: JSONException) {
            Log.w(TAG, "JSON parse failed for: ${raw.take(200)}")
            Result.Failure("Model did not return valid JSON")
        }
    }

    /**
     * Forgiving JSON parse: some providers wrap the JSON in markdown fences or
     * add a short preamble.  Strip the obvious wrappers before handing to
     * [JSONObject].  Missing fields fall back to safe defaults.
     */
    internal fun parse(raw: String): ScreenAnalysis {
        val trimmed = stripFences(raw)
        val obj = JSONObject(trimmed)

        val entities = obj.optJSONObject("entities") ?: JSONObject()
        return ScreenAnalysis(
            summary        = obj.optString("summary").trim(),
            appName        = obj.optString("app_name").trim().ifBlank { "unknown" },
            screenType     = obj.optString("screen_type").trim().ifBlank { "unknown" },
            userIntent     = obj.optString("user_intent").trim(),
            importantText  = obj.optJSONArray("important_text").toStringList(),
            actionItems    = obj.optJSONArray("action_items").toStringList(),
            people         = entities.optJSONArray("people").toStringList(),
            brands         = entities.optJSONArray("brands").toStringList(),
            products       = entities.optJSONArray("products").toStringList(),
            urls           = entities.optJSONArray("urls").toStringList(),
            emails         = entities.optJSONArray("emails").toStringList(),
            sensitive      = obj.optBoolean("sensitive", false),
            confidence     = obj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
            rawJson        = trimmed
        )
    }

    private fun stripFences(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("```")) return t
        // Drop opening fence line (```json or ```)
        val afterOpen = t.substringAfter('\n', missingDelimiterValue = "")
        val withoutClose = afterOpen.substringBeforeLast("```").trim()
        return withoutClose
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i).trim()
            if (s.isNotBlank()) out += s
        }
        return out
    }
}
