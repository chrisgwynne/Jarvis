package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import com.jarvis.assistant.camera.CameraCaptureManager
import com.jarvis.assistant.camera.CaptureResult
import com.jarvis.assistant.camera.VisionClient
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.LlmRouter
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * AnalyzeCameraViewTool — "What do you see?" / "Look at this."
 *
 * PIPELINE:
 *   1. Capture a still frame via the rear camera (CameraCaptureManager).
 *   2. Scale + base64-encode the image (VisionClient.encodeImageFile — on IO thread).
 *   3. POST to the active provider's vision endpoint (OpenAI or Anthropic).
 *   4. Return the model's description as a spoken ToolResult.Success.
 *
 * FAILURE ISOLATION:
 *   - Capture failure   → ToolResult.Failure, image NOT saved (nothing to analyse).
 *   - Analysis failure  → ToolResult.Failure, but the captured image IS preserved on disk.
 *     The user can still find it with CameraCaptureTool if needed.
 *
 * PROVIDER REQUIREMENT:
 *   Requires OpenAI (gpt-4o) or Anthropic (claude-haiku-4-5). Other providers return
 *   a structured failure with a clear "switch provider" message.
 */
/**
 * @param llmRouter When non-null, images are sent through the main LLM pipeline
 *   (supports all vision-capable providers including Gemini). When null, falls
 *   back to [visionClient] (OpenAI / Anthropic / OpenRouter only).
 */
class AnalyzeCameraViewTool(
    private val context: Context,
    private val captureManager: CameraCaptureManager,
    private val visionClient: VisionClient,
    private val llmRouter: LlmRouter? = null
) : Tool {

    override val name           = "analyze_camera_view"
    override val description    = "Captures a photo and describes what's visible"
    override val requiresNetwork = true
    override val requiredPermissions = listOf(Manifest.permission.CAMERA)

    override fun schema() = ToolSchema(
        name        = name,
        description = "Take a photo with the rear camera and describe what's visible. Use when the user asks what you see or to look at something.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any>(),
            "required" to emptyList<String>()
        )
    )

    companion object {
        private const val TAG = "AnalyzeCameraViewTool"

        /**
         * Short, conversational vision prompt.
         * Keeps model responses within the 1-3 sentence voice output target.
         */
        private const val VISION_PROMPT =
            "Describe what you see in this image in 1-2 short, conversational sentences. Be concise."

        private val TRIGGERS = Regex(
            """what\s+(?:do|can|did)\s+you\s+see""" +
            """|look\s+at\s+this""" +
            """|describe\s+(?:what\s+you\s+see|this)""" +
            """|what(?:'s|\s+is)\s+(?:in\s+front(?:\s+of\s+(?:you|me))?|there|this)""" +
            // Both US ("analyze") and UK ("analyse") spellings, with or without "this/the image"
            """|analy[sz]e\s+(?:this|the\s+(?:image|photo|picture))""" +
            """|can\s+you\s+analy[sz]e""" +
            """|what(?:'s|\s+is)\s+in\s+(?:this|the)\s+(?:image|photo|picture)""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun matches(transcript: String): ToolInput? =
        if (TRIGGERS.containsMatchIn(transcript.trim())) ToolInput(transcript.trim()) else null

    override suspend fun execute(input: ToolInput): ToolResult {
        Log.d(TAG, "Capturing frame for vision analysis")

        // Step 1 — capture
        val captureResult = captureManager.capturePhoto(CameraSelector.LENS_FACING_BACK)
        if (captureResult is CaptureResult.Failure) {
            return ToolResult.Failure("I couldn't open the camera. ${captureResult.reason.take(60)}")
        }
        val file = (captureResult as CaptureResult.Success).file
        Log.d(TAG, "Captured ${file.name}, sending to vision API")

        // Step 2+3 — encode + analyse (analysis failure is isolated; image is preserved)
        return try {
            val description = if (llmRouter != null) {
                // Route through main LLM pipeline — supports Gemini and all other providers
                val base64 = visionClient.encodeImageForPipeline(file)
                val systemMsg = Message("system",
                    "You are Jarvis. Talk like a person, not an assistant. " +
                    "Describe what you see in 1-2 short, natural sentences. " +
                    "No assistant phrasing, no 'I can see…' preambles — just say it.")
                val userMsg = Message("user", VISION_PROMPT, imageBase64 = base64)
                llmRouter.completeSilent(listOf(systemMsg, userMsg))
            } else {
                visionClient.analyze(file, VISION_PROMPT)
            }
            Log.d(TAG, "Vision result: ${description.take(100)}")
            ToolResult.Success(description)
        } catch (e: LlmException) {
            Log.w(TAG, "Vision analysis failed: ${e.message}")
            ToolResult.Failure(e.message ?: "Image analysis failed.")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected vision error", e)
            ToolResult.Failure("Image was captured but I couldn't analyse it.")
        }
    }
}
