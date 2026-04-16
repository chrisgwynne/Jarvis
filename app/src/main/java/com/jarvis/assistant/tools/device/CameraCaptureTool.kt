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
         * Covers: "selfie", "take a selfie", "take my selfie", "take me a selfie",
         *         "snap a selfie", "take a quick selfie", "take a front photo", etc.
         * Checked FIRST because "selfie" is unambiguous.
         *
         * The `selfie\b` catch-all at the end ensures ANY transcript that contains
         * the word "selfie" maps to the front camera, regardless of phrasing.
         */
        private val FRONT_TRIGGERS = Regex(
            """(?:take|snap|capture)\s+(?:(?:me\s+a?|my|a|an|the)\s+)?(?:quick\s+)?(?:selfie|front\s+(?:photo|pic(?:ture)?|image|shot))""" +
            """|selfie\b""",
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
                ToolResult.Success("Got it, $label taken.")
            }
            is CaptureResult.Failure -> {
                Log.w(TAG, "$label failed: ${result.reason}")
                ToolResult.Failure("Couldn't take the $label. ${result.reason.take(80)}")
            }
        }
    }
}
