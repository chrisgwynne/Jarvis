package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import com.jarvis.assistant.camera.CameraCaptureManager
import com.jarvis.assistant.camera.CaptureResult
import com.jarvis.assistant.camera.VisionClient
import com.jarvis.assistant.llm.LlmRouter
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.Message
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import com.jarvis.assistant.vision.VisualContextStore

/**
 * SelfieCaptureTool — captures a still frame via the front (selfie) camera.
 *
 * PIPELINE:
 *   1. Capture via [CameraCaptureManager] using [CameraSelector.LENS_FACING_FRONT].
 *   2. Optionally analyse the frame with the vision model (when [llmRouter] or
 *      [visionClient] is available), or simply confirm capture.
 *   3. Store in [VisualContextStore] so follow-up commands ("send that") work.
 *
 * TRIGGERS:
 *   "take a selfie", "selfie", "front camera photo"
 *   These are intentionally NOT matched by [AnalyzeCameraViewTool] which only
 *   uses the rear camera — no trigger conflict.
 */
class SelfieCaptureTool(
    private val context: Context,
    private val captureManager: CameraCaptureManager,
    private val visionClient: VisionClient? = null,
    private val llmRouter: LlmRouter? = null,
    private val visualContextStore: VisualContextStore? = null,
) : Tool {

    override val name            = "selfie_capture"
    override val description     = "Takes a selfie using the front camera"
    override val requiresNetwork = false
    override val requiredPermissions = listOf(Manifest.permission.CAMERA)

    override fun schema() = ToolSchema(
        name        = name,
        description = "Capture a photo with the front (selfie) camera. Use when the user says " +
                      "'take a selfie', 'front camera photo', or similar.",
        parameters  = mapOf(
            "type"       to "object",
            "properties" to emptyMap<String, Any>(),
            "required"   to emptyList<String>(),
        )
    )

    companion object {
        private const val TAG = "SelfieCaptureTool"

        private const val ANALYSE_PROMPT =
            "Describe what you see in this selfie in one friendly, natural sentence."

        private val TRIGGERS = Regex(
            """take\s+a\s+selfie""" +
            """|selfie\s+(?:photo|picture|shot)?""" +
            """|front\s+camera\s+(?:photo|picture|shot)?""" +
            """|photo\s+of\s+(?:my|me)\s+(?:face|self)?""" +
            """|(?:picture|photo)\s+of\s+me(?:\s+please)?""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun matches(transcript: String): ToolInput? =
        if (TRIGGERS.containsMatchIn(transcript.trim())) ToolInput(transcript.trim()) else null

    override suspend fun execute(input: ToolInput): ToolResult {
        Log.d(TAG, "Capturing selfie (front camera)")
        val captureResult = captureManager.capturePhoto(CameraSelector.LENS_FACING_FRONT)
        if (captureResult is CaptureResult.Failure) {
            return ToolResult.Failure(
                "Couldn't open the front camera. ${captureResult.reason.take(60)}"
            )
        }
        val file = (captureResult as CaptureResult.Success).file
        Log.d(TAG, "[SELFIE_CAPTURED] file=${file.name}")

        visualContextStore?.update(
            VisualContextStore.VisualContext(
                source        = VisualContextStore.Source.FRONT_CAMERA,
                imageFilePath = file.absolutePath,
                capturedAtMs  = System.currentTimeMillis(),
            )
        )

        // Optional analysis — if a vision provider is configured, describe the selfie
        val description: String? = if (llmRouter != null && visionClient != null) {
            try {
                val base64  = visionClient.encodeImageForPipeline(file)
                val sysMsg  = Message("system",
                    "You are Jarvis. Describe the selfie naturally in one sentence.")
                val userMsg = Message("user", ANALYSE_PROMPT, imageBase64 = base64)
                llmRouter.completeSilent(listOf(sysMsg, userMsg))
            } catch (e: LlmException) {
                Log.w(TAG, "Vision analysis failed — selfie saved without description")
                null
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error during selfie analysis", e)
                null
            }
        } else null

        visualContextStore?.enrichCurrent(summary = description)

        return ToolResult.Success(description ?: "Selfie taken.")
    }
}
