package com.jarvis.assistant.tools.device

import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import com.jarvis.assistant.vision.ScreenObservationRepository
import com.jarvis.assistant.vision.ScreenshotCaptureService
import com.jarvis.assistant.vision.VisionScreenAnalyzer

/**
 * LookAtThisIntentHandler — the "look at this" voice intent.
 *
 * PIPELINE:
 *   1. [ScreenshotCaptureService] grabs a PNG via the Accessibility API and
 *      writes it to app-private storage.
 *   2. [VisionScreenAnalyzer] runs the multimodal model with a JSON-only
 *      prompt and parses the response into a [VisionScreenAnalyzer.ScreenAnalysis].
 *   3. We speak a 1-sentence summary (redacted to a safe sentence when the
 *      analyzer flagged the screen as sensitive).
 *   4. [ScreenObservationRepository] persists the observation — but only when
 *      `sensitive == false` and `confidence ≥ 0.65`.
 *
 * TRIGGER-CONFLICT RESOLUTION:
 *   The camera-vision tool ([AnalyzeCameraViewTool]) also claimed
 *   "look at this".  This handler is registered BEFORE it in
 *   [com.jarvis.assistant.tools.framework.ToolRegistry.buildDefault], so
 *   "look at this" now captures the screen.  Camera-specific intents
 *   ("look through the camera", "describe this photo") still reach the
 *   camera tool via its other triggers.
 *
 * FAILURE ISOLATION:
 *   * Accessibility not connected → spoken hint to enable it in Settings.
 *   * Snapshot unavailable (API<30 etc.) → friendly "can't see the screen" reply.
 *   * Vision call or JSON parse fails → friendly error, nothing written.
 *   * Sensitive screen → spoken acknowledgement only; no image reference, no
 *     extracted text, no stored memory.
 *
 * NEVER-SPOKEN CONTENT:
 *   When sensitive is true we do not read back important_text, urls, or
 *   entities — even if the analyzer misclassified a screen as "not a login"
 *   but also left the sensitive flag set.  The spoken reply is a neutral
 *   one-liner.
 */
class LookAtThisIntentHandler(
    private val screenshotCapture: ScreenshotCaptureService,
    private val analyzer:          VisionScreenAnalyzer,
    private val repository:        ScreenObservationRepository
) : Tool {

    override val name            = "look_at_this"
    override val description     = "Analyses the current screen and remembers what's on it"
    override val requiresNetwork = true   // the vision call hits the LLM provider
    override val requiredPermissions: List<String> = emptyList()

    override fun schema() = ToolSchema(
        name        = name,
        description = "Capture and analyse what is currently on the user's screen. " +
                      "Use when the user says 'look at this', 'look at my screen', or " +
                      "asks what is visible on the phone right now.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>(),
            "required" to emptyList<String>()
        )
    )

    companion object {
        private const val TAG = "LookAtThis"

        /**
         * Triggers.  Registered BEFORE AnalyzeCameraViewTool so screen wins
         * for the generic "look at this" phrase.  The camera-specific
         * phrasings ("look through the camera", "what do you see in front of
         * you") are NOT matched here, so the camera tool retains them.
         */
        private val TRIGGERS = Regex(
            """look\s+at\s+this""" +
            """|look\s+at\s+my\s+screen""" +
            """|read\s+(?:my|this|the)\s+screen""" +
            """|what(?:'s|\s+is)\s+on\s+(?:my\s+)?screen""" +
            """|analy[sz]e\s+(?:my|this|the)\s+screen""" +
            """|describe\s+(?:my|this|the)\s+screen""",
            RegexOption.IGNORE_CASE
        )

        /** Safe reply when the screen carries credentials or payment details. */
        private const val SENSITIVE_REPLY =
            "Looks like a screen with sensitive details — leaving it be."
    }

    override fun matches(transcript: String): ToolInput? =
        if (TRIGGERS.containsMatchIn(transcript.trim())) ToolInput(transcript.trim()) else null

    override suspend fun execute(input: ToolInput): ToolResult {
        Log.d(TAG, "Triggered — capturing screen")

        // Step 1 — capture
        val capture = when (val r = screenshotCapture.capture()) {
            is ScreenshotCaptureService.Result.Success     -> r
            is ScreenshotCaptureService.Result.Unavailable -> {
                Log.w(TAG, "Capture unavailable: ${r.reason}")
                return ToolResult.Failure(
                    "I can't see your screen. Enable the Jarvis accessibility service in Settings."
                )
            }
            is ScreenshotCaptureService.Result.Failure -> {
                Log.w(TAG, "Capture failed: ${r.reason}")
                return ToolResult.Failure("Something blocked the screenshot. Try again in a sec.")
            }
        }

        // Step 2 — analyse
        val analysis = when (val r = analyzer.analyze(capture.file)) {
            is VisionScreenAnalyzer.Result.Success -> r.analysis
            is VisionScreenAnalyzer.Result.Failure -> {
                Log.w(TAG, "Analyze failed: ${r.reason}")
                return ToolResult.Failure("I got the screenshot but couldn't read it.")
            }
        }

        // Step 3 — save (or deliberately skip)
        val saveResult = repository.save(
            analysis           = analysis,
            screenshotPath     = capture.file.absolutePath,
            foregroundPackage  = capture.snapshot.foregroundPackage,
            capturedAtMs       = capture.snapshot.capturedAtMs
        )
        when (saveResult) {
            is ScreenObservationRepository.SaveResult.Saved                 ->
                Log.d(TAG, "Persisted observation id=${saveResult.id}")
            ScreenObservationRepository.SaveResult.SkippedSensitive         ->
                Log.i(TAG, "Not persisting — sensitive screen")
            is ScreenObservationRepository.SaveResult.SkippedLowConfidence  ->
                Log.i(TAG, "Not persisting — low confidence ${saveResult.confidence}")
        }

        // Step 4 — speak. Never echo extracted text when the screen was sensitive.
        val spoken = if (analysis.sensitive) SENSITIVE_REPLY
                     else analysis.summary.ifBlank { "Had a look — not much I can say about that one." }

        return ToolResult.Success(spokenFeedback = spoken)
    }
}
