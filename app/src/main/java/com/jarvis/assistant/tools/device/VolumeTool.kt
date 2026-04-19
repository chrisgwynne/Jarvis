package com.jarvis.assistant.tools.device

import android.content.Context
import android.media.AudioManager as SysAudioManager
import com.jarvis.assistant.runtime.FailurePhrases
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

class VolumeTool(private val context: Context) : Tool {

    override val name = "volume_control"
    override val description = "Raise, lower, or mute the phone volume"

    private val UP_RE   = Regex("""(?:turn\s+up|raise|increase|louder)\s*(?:the\s+)?volume|volume\s+up""", RegexOption.IGNORE_CASE)
    private val DOWN_RE = Regex("""(?:turn\s+down|lower|decrease|quieter)\s*(?:the\s+)?volume|volume\s+down""", RegexOption.IGNORE_CASE)
    private val MUTE_RE = Regex("""(?:mute|silence)\s+(?:the\s+)?(?:phone|sound|volume|ringer)|mute\s+(?:me|my\s+phone)""", RegexOption.IGNORE_CASE)

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        val dir = when {
            UP_RE.containsMatchIn(t)   -> "up"
            DOWN_RE.containsMatchIn(t) -> "down"
            MUTE_RE.containsMatchIn(t) -> "mute"
            else -> return null
        }
        return ToolInput(transcript, mapOf("direction" to dir))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val am = context.getSystemService(SysAudioManager::class.java)
            ?: return ToolResult.Failure(FailurePhrases.AUDIO_SERVICE_UNAVAILABLE)
        return when (input.param("direction")) {
            "up" -> {
                am.adjustStreamVolume(SysAudioManager.STREAM_MUSIC, SysAudioManager.ADJUST_RAISE, SysAudioManager.FLAG_SHOW_UI)
                am.adjustStreamVolume(SysAudioManager.STREAM_RING, SysAudioManager.ADJUST_RAISE, 0)
                ToolResult.Success("Volume raised.", silent = true)
            }
            "down" -> {
                am.adjustStreamVolume(SysAudioManager.STREAM_MUSIC, SysAudioManager.ADJUST_LOWER, SysAudioManager.FLAG_SHOW_UI)
                am.adjustStreamVolume(SysAudioManager.STREAM_RING, SysAudioManager.ADJUST_LOWER, 0)
                ToolResult.Success("Volume lowered.", silent = true)
            }
            else -> {
                am.adjustStreamVolume(SysAudioManager.STREAM_RING, SysAudioManager.ADJUST_MUTE, SysAudioManager.FLAG_SHOW_UI)
                ToolResult.Success("Phone muted.", silent = true)
            }
        }
    }
}
