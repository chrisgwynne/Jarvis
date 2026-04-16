package com.jarvis.assistant.tools.device

import android.content.Context
import android.hardware.camera2.CameraManager
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

class FlashlightTool(private val context: Context) : Tool {

    override val name = "flashlight"
    override val description = "Turn the flashlight/torch on or off"

    private val ON_RE  = Regex("""(?:turn|switch|put)\s+on\s+(?:the\s+)?(?:flashlight|torch|light)|(?:flashlight|torch)\s+on""", RegexOption.IGNORE_CASE)
    private val OFF_RE = Regex("""(?:turn|switch|put)\s+off\s+(?:the\s+)?(?:flashlight|torch|light)|(?:flashlight|torch)\s+off""", RegexOption.IGNORE_CASE)

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
            val cm       = context.getSystemService(CameraManager::class.java)!!
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: return ToolResult.Failure("No camera available for flashlight.")
            cm.setTorchMode(cameraId, on)
            ToolResult.Success(if (on) "Flashlight on." else "Flashlight off.", silent = true)
        } catch (e: Exception) {
            ToolResult.Failure("Couldn't toggle flashlight: ${e.message}")
        }
    }
}
