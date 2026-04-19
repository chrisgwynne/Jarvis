package com.jarvis.assistant.tools.device

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.jarvis.assistant.runtime.FailurePhrases
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

class FlashlightTool(private val context: Context) : Tool {

    override val name = "flashlight"
    override val description = "Turn the flashlight/torch on or off"

    private val ON_RE  = Regex("""(?:turn|switch|put)\s+on\s+(?:the\s+)?(?:flashlight|torch)|(?:flashlight|torch)\s+on""", RegexOption.IGNORE_CASE)
    private val OFF_RE = Regex("""(?:turn|switch|put)\s+off\s+(?:the\s+)?(?:flashlight|torch)|(?:flashlight|torch)\s+off""", RegexOption.IGNORE_CASE)

    // Cameras don't change at runtime, but CameraCharacteristics is an IPC
    // lookup for every id — cache the first flash-capable id after the first
    // successful resolve so the toggle path is O(1).
    @Volatile private var flashCameraId: String? = null

    private fun resolveFlashCameraId(cm: CameraManager): String? {
        flashCameraId?.let { return it }
        val hit = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        flashCameraId = hit
        return hit
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        val on = when {
            ON_RE.containsMatchIn(t)  -> "true"
            OFF_RE.containsMatchIn(t) -> "false"
            else -> return null
        }
        return ToolInput(transcript, mapOf("on" to on))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val on = input.param("on") == "true"
        return try {
            val cm = context.getSystemService(CameraManager::class.java)
                ?: return ToolResult.Failure(FailurePhrases.CAMERA_SERVICE_UNAVAILABLE)
            val cameraId = resolveFlashCameraId(cm)
                ?: return ToolResult.Failure(FailurePhrases.NO_FLASH)
            cm.setTorchMode(cameraId, on)
            ToolResult.Success(if (on) "Flashlight on." else "Flashlight off.", silent = true)
        } catch (e: Exception) {
            Log.w("FlashlightTool", "Toggle failed", e)
            ToolResult.Failure(FailurePhrases.FLASHLIGHT_DIDNT_RESPOND)
        }
    }
}
