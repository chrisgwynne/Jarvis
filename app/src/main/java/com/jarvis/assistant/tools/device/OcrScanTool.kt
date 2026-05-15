package com.jarvis.assistant.tools.device

import android.Manifest
import android.util.Log
import androidx.camera.core.CameraSelector
import com.jarvis.assistant.camera.CameraCaptureManager
import com.jarvis.assistant.camera.CaptureResult
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import com.jarvis.assistant.vision.OcrPipeline
import com.jarvis.assistant.vision.VisualContextStore
import java.io.File

/**
 * OcrScanTool — reads text from an image.
 *
 * SOURCE PRIORITY:
 *   1. Recent visual context ([VisualContextStore]) — if a screenshot or photo
 *      was captured within the expiry window, use that image directly.
 *   2. Camera capture — capture a new rear-camera frame for OCR.
 *
 * PIPELINE:
 *   1. Resolve image source (context store → camera capture).
 *   2. Run [OcrPipeline.extractText] with the appropriate mode.
 *   3. Update [VisualContextStore] with the OCR result.
 *   4. Speak a concise response (truncated for voice output).
 *
 * MODE DETECTION:
 *   "read this error" / "what's wrong" → [OcrPipeline.Mode.ERROR]
 *   "read the label" / "read the sign" → [OcrPipeline.Mode.LABEL]
 *   Everything else                    → [OcrPipeline.Mode.GENERAL]
 *
 * LOCAL-FIRST:
 *   Requires network only because [OcrPipeline] currently routes through the
 *   vision model.  When a local ML Kit provider is added, set
 *   [requiresNetwork] = false for the local path.
 */
class OcrScanTool(
    private val captureManager: CameraCaptureManager,
    private val ocrPipeline: OcrPipeline,
    private val visualContextStore: VisualContextStore? = null,
) : Tool {

    override val name            = "ocr_scan"
    override val description     = "Reads text from the camera view or the most recent captured image"
    override val requiresNetwork = true
    override val requiredPermissions = listOf(Manifest.permission.CAMERA)

    override fun schema() = ToolSchema(
        name        = name,
        description = "Read, scan, or extract text from what the camera sees or the last captured image.",
        parameters  = mapOf(
            "type"       to "object",
            "properties" to emptyMap<String, Any>(),
            "required"   to emptyList<String>(),
        )
    )

    companion object {
        private const val TAG = "OcrScanTool"
        private const val MAX_SPOKEN_CHARS = 400

        private val TRIGGERS = Regex(
            """read\s+this""" +
            """|what\s+does\s+this\s+say""" +
            """|what(?:'s|\s+is)\s+(?:written|on)\s+(?:it|there|this)""" +
            """|scan\s+this""" +
            """|extract\s+(?:the\s+)?text""" +
            """|read\s+(?:the\s+)?(?:label|sign|notice|poster|price|tag)""" +
            """|what\s+does\s+(?:the\s+)?(?:label|sign|notice|poster|it)\s+say""" +
            """|(?:read|scan|transcribe)\s+(?:this\s+)?document""" +
            """|what\s+does\s+it\s+say""",
            RegexOption.IGNORE_CASE
        )

        private val ERROR_HINT = Regex(
            """error|wrong|problem|issue|what(?:'s|\s+is)\s+wrong""",
            RegexOption.IGNORE_CASE
        )
        private val LABEL_HINT = Regex(
            """label|sign|notice|poster|price|tag|sticker""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun matches(transcript: String): ToolInput? =
        if (TRIGGERS.containsMatchIn(transcript.trim())) ToolInput(transcript.trim()) else null

    override suspend fun execute(input: ToolInput): ToolResult {
        Log.d(TAG, "OCR scan triggered: \"${input.transcript.take(60)}\"")

        // Determine OCR mode from the utterance
        val mode = when {
            ERROR_HINT.containsMatchIn(input.transcript) -> OcrPipeline.Mode.ERROR
            LABEL_HINT.containsMatchIn(input.transcript) -> OcrPipeline.Mode.LABEL
            else -> OcrPipeline.Mode.GENERAL
        }

        // Resolve image source
        val imageFile = resolveImageSource() ?: return ToolResult.Failure(
            "I couldn't get an image to read. Try again."
        )

        // Run OCR
        val result = ocrPipeline.extractText(imageFile, mode)
        if (!result.hasText) {
            return ToolResult.Success("I couldn't find any readable text in that image.")
        }

        // Update visual context with OCR result
        visualContextStore?.enrichCurrent(ocrText = result.text)

        // Build spoken response — truncate for voice
        val spoken = if (result.text.length <= MAX_SPOKEN_CHARS) {
            "It says: ${result.text}"
        } else {
            "Here's what it says: ${result.text.take(MAX_SPOKEN_CHARS)}…"
        }

        Log.d(TAG, "[OCR_COMPLETE] mode=$mode chars=${result.text.length}")
        return ToolResult.Success(
            spokenFeedback = spoken,
            rawData        = mapOf("ocr_text" to result.text, "mode" to mode.name),
        )
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /** Use current visual context image if fresh; otherwise capture via camera. */
    private suspend fun resolveImageSource(): File? {
        // 1. Check visual context store for a recent image
        val ctx = visualContextStore?.current
        if (ctx?.imageFilePath != null) {
            val file = File(ctx.imageFilePath)
            if (file.exists()) {
                Log.d(TAG, "[OCR_SOURCE] using visual context: ${file.name}")
                return file
            }
        }

        // 2. Capture new frame via rear camera
        Log.d(TAG, "[OCR_SOURCE] capturing new camera frame")
        val captureResult = captureManager.capturePhoto(CameraSelector.LENS_FACING_BACK)
        if (captureResult is CaptureResult.Failure) {
            Log.w(TAG, "[OCR_CAPTURE_FAILED] ${captureResult.reason}")
            return null
        }
        val file = (captureResult as CaptureResult.Success).file

        // Store the fresh capture in visual context
        visualContextStore?.update(
            VisualContextStore.VisualContext(
                source        = VisualContextStore.Source.PHONE_CAMERA,
                imageFilePath = file.absolutePath,
                capturedAtMs  = System.currentTimeMillis(),
            )
        )
        return file
    }
}
