package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import com.jarvis.assistant.camera.CameraCaptureManager
import com.jarvis.assistant.camera.CaptureResult
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * CameraCaptureTool — handles "take a photo" and "take a selfie".
 *
 * TRIGGER EXAMPLES:
 *   Rear:  "take a photo", "take my photo", "take me a photo", "take a quick photo",
 *          "snap a picture", "take a pic", "capture an image", "take a shot"
 *   Front: "take a selfie", "take my selfie", "take me a selfie", "snap a selfie", "selfie"
 *
 * STORAGE:
 *   Saves to app-private filesDir/pictures — no external storage permission needed.
 *   Also published to MediaStore gallery (Pictures/Jarvis) via CameraCaptureManager.
 *
 * LOCKED PHONE:
 *   Works on a locked phone as long as JarvisService is running as a foreground
 *   service with foregroundServiceType="microphone|camera" and the
 *   FOREGROUND_SERVICE_CAMERA permission is declared. Both are set in the manifest.
 *
 * PERMISSIONS:
 *   CAMERA is checked by ToolRegistry before execute() is called.
 *   On API < 29, WRITE_EXTERNAL_STORAGE is also required for gallery publishing.
 */
class CameraCaptureTool(
    private val context: Context,
    private val captureManager: CameraCaptureManager
) : Tool {

    override val name        = "camera_capture"
    override val description = "Takes a still photo using the device camera and saves it"

    override fun schema() = ToolSchema(
        name        = name,
        description = "Take a photo with the device camera and save it. Use facing=front for selfies, facing=rear for normal photos.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "facing" to mapOf(
                    "type" to "string",
                    "enum" to listOf("front", "rear"),
                    "description" to "Which camera to use"
                )
            ),
            "required" to listOf("facing")
        )
    )
    override val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        // Gallery publishing on Android 8/9 needs WRITE_EXTERNAL_STORAGE.
        // On Android 10+ MediaStore inserts work without it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    companion object {
        private const val TAG = "CameraCaptureTool"

        /**
         * FRONT / selfie triggers.
         *
         * REQUIRES a capture verb (take / snap / capture / grab).  An
         * earlier version had a bare `|selfie\b` catch-all so ANY
         * utterance containing "selfie" matched — which made
         * "show me the selfie" fire the camera AGAIN instead of
         * routing to ViewMediaTool.  That was the reported bug.
         *
         * Accepts the "another / new / one more" qualifiers so
         * "take another selfie" still routes here.
         */
        private val FRONT_TRIGGERS = Regex(
            """(?:take|snap|capture|grab)\s+(?:(?:me\s+a?|my|a|an|the|another)\s+)?(?:quick\s+|new\s+|one\s+more\s+)?(?:selfie|front\s+(?:photo|pic(?:ture)?|image|shot))""",
            RegexOption.IGNORE_CASE
        )

        /**
         * REAR / back camera triggers.
         * Covers: "take a photo", "take my photo", "take me a photo", "snap a pic",
         *         "take a quick picture", "take a shot", "capture an image", etc.
         */
        private val REAR_TRIGGERS = Regex(
            """(?:take|snap|capture)\s+(?:(?:me\s+a?|my|a|an|the)\s+)?(?:quick\s+)?(?:photo|picture|image|shot|pic)\b""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        return when {
            FRONT_TRIGGERS.containsMatchIn(t) -> {
                Log.d(TAG, "Front-camera trigger matched: \"${t.take(60)}\"")
                ToolInput(t, mapOf("facing" to "front"))
            }
            REAR_TRIGGERS.containsMatchIn(t) -> {
                Log.d(TAG, "Rear-camera trigger matched: \"${t.take(60)}\"")
                ToolInput(t, mapOf("facing" to "rear"))
            }
            else -> null
        }
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val isFront    = input.param("facing") == "front"
        val lensFacing = if (isFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val label      = if (isFront) "selfie" else "photo"

        Log.d(TAG, "Capturing $label (lens=${if (isFront) "front" else "rear"})")

        return when (val result = captureManager.capturePhoto(lensFacing)) {
            is CaptureResult.Success -> {
                Log.i(TAG, "$label saved: ${result.file.name}")
                // Publish the captured file path so contextual
                // follow-ups ("show me the selfie", "share that")
                // resolve against this capture.  rawData carries the
                // path back to the runtime so RecentActionContextStore
                // picks it up without reaching into MediaContextStore.
                com.jarvis.assistant.tools.device.media.MediaContextStore.record(
                    com.jarvis.assistant.tools.device.media.MediaContextStore.Entry(
                        filePath  = result.file.absolutePath,
                        mimeType  = "image/jpeg",
                        kind      = if (isFront) "selfie" else "photo",
                    )
                )
                Log.d(TAG, "[MEDIA_URI_CAPTURED] kind=$label path=${result.file.absolutePath}")
                ToolResult.Success(
                    spokenFeedback = "Got it, $label taken.",
                    rawData        = result.file.absolutePath,
                )
            }
            is CaptureResult.Failure -> {
                Log.w(TAG, "$label failed: ${result.reason}")
                ToolResult.Failure("Couldn't take the $label. ${result.reason.take(80)}")
            }
        }
    }
}
