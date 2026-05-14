package com.jarvis.assistant.tools.device

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * FindPhoneTool — "find my phone".  Useful when the user has already
 * picked the phone up to talk to Jarvis but is reminding themselves where
 * it normally lives (yes, really) — or, more practically, when somebody
 * has set up a smart speaker and asks via a paired channel.
 *
 * Strategy: 30 seconds of max-volume ringtone playback + flashlight
 * strobe.  All purely local — no Find-My-Device cloud round-trip.
 *
 * The audio focus + ringer-mode dance is intentional: silent mode would
 * otherwise mute everything.
 */
class FindPhoneTool(
    private val context: Context,
    private val durationMs: Long = 30_000L,
) : Tool {

    override val name = "find_phone"
    override val description = "Ring + flash the phone for 30 seconds so it can be located."
    override val requiresNetwork = false

    companion object {
        private const val TAG = "FindPhoneTool"
        private val FIND_RX = Regex(
            """\b(?:find|where(?:'?s|\s+is)|locate|ring)\s+(?:my\s+)?phone\b|\bsound\s+the\s+alarm\b|\b(?:make\s+(?:the|my)\s+phone|phone)\s+(?:ring|make\s+noise)\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun matches(transcript: String): ToolInput? =
        if (FIND_RX.containsMatchIn(transcript.trim())) ToolInput(transcript) else null

    override fun schema() = ToolSchema(
        name        = name,
        description = "Ring the phone at maximum volume with a flashlight strobe so it can be located.",
        parameters  = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        return try {
            startRinger()
            startStrobe()
            ToolResult.Success("Ringing the phone for thirty seconds.")
        } catch (e: Exception) {
            Log.w(TAG, "FindPhone failed", e)
            ToolResult.Failure("That didn't work — couldn't ring the phone.")
        }
    }

    private fun startRinger() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        // Bump ringer mode out of silent/vibrate, max ring stream volume.
        @Suppress("DEPRECATION")
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        am.setStreamVolume(
            AudioManager.STREAM_RING,
            am.getStreamMaxVolume(AudioManager.STREAM_RING),
            0,
        )
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ring = RingtoneManager.getRingtone(context, uri) ?: return
        ring.play()
        Handler(Looper.getMainLooper()).postDelayed({
            try { ring.stop() } catch (_: Exception) {}
        }, durationMs)
    }

    private fun startStrobe() {
        val cm = context.getSystemService(CameraManager::class.java) ?: return
        val flashId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        val handler = Handler(Looper.getMainLooper())
        val end = System.currentTimeMillis() + durationMs
        lateinit var tick: Runnable
        var on = false
        tick = Runnable {
            try {
                on = !on
                cm.setTorchMode(flashId, on)
            } catch (_: Exception) {}
            if (System.currentTimeMillis() < end) {
                handler.postDelayed(tick, 300L)
            } else {
                try { cm.setTorchMode(flashId, false) } catch (_: Exception) {}
            }
        }
        handler.post(tick)
    }
}
