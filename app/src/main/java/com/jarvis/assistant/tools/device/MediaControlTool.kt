package com.jarvis.assistant.tools.device

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.jarvis.assistant.runtime.FailurePhrases
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

class MediaControlTool(private val context: Context) : Tool {

    override val name = "media_control"
    override val description = "Control media playback — play, pause, skip, previous"
    override val requiresNetwork = false
    override val requiredPermissions: List<String> = emptyList()

    private val PAUSE_RE    = Regex("""(?:pause(?:\s+(?:music|that))?|stop\s+(?:music|playing))""", RegexOption.IGNORE_CASE)
    private val PLAY_RE     = Regex("""^(?:play|resume(?:\s+music)?|unpause)${'$'}""", RegexOption.IGNORE_CASE)
    private val SKIP_RE     = Regex("""(?:skip(?:\s+this)?|next\s+(?:song|track))""", RegexOption.IGNORE_CASE)
    private val PREVIOUS_RE = Regex("""(?:previous(?:\s+song)?|go\s+back|last\s+song)""", RegexOption.IGNORE_CASE)
    private val SHUFFLE_RE  = Regex("""(?:shuffle(?:\s+on)?|turn\s+on\s+shuffle)""", RegexOption.IGNORE_CASE)
    private val REPEAT_RE   = Regex("""repeat(?:\s+this)?""", RegexOption.IGNORE_CASE)

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        val action = when {
            PAUSE_RE.containsMatchIn(t)    -> "pause"
            PLAY_RE.containsMatchIn(t)     -> "play"
            SKIP_RE.containsMatchIn(t)     -> "skip"
            PREVIOUS_RE.containsMatchIn(t) -> "previous"
            SHUFFLE_RE.containsMatchIn(t)  -> "shuffle"
            REPEAT_RE.containsMatchIn(t)   -> "repeat"
            else -> return null
        }
        return ToolInput(transcript, mapOf("action" to action))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val action = input.param("action")

        val keycode = when (action) {
            "pause"    -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play"     -> KeyEvent.KEYCODE_MEDIA_PLAY
            "skip"     -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "shuffle"  -> KeyEvent.KEYCODE_MEDIA_PLAY   // best-effort toggle via key event
            "repeat"   -> KeyEvent.KEYCODE_MEDIA_PLAY   // best-effort; app handles toggle state
            else       -> KeyEvent.KEYCODE_MEDIA_PLAY
        }

        return try {
            // Try MediaSessionManager first (gives us a direct controller, no extra permission needed
            // for reading the active session — MEDIA_CONTENT_CONTROL is only required for listing
            // ALL sessions; getActiveSessions() gracefully throws SecurityException if denied).
            val dispatched = tryDispatchViaMediaSession(keycode)

            if (!dispatched) {
                // Fallback: inject key events through AudioManager (works system-wide)
                dispatchViaAudioManager(keycode)
            }

            // silent = true: skip TTS confirmation so we don't steal audio focus
            // from the very media we just started/paused.  The user can hear
            // the result (music plays or stops) without Jarvis speaking over it.
            val spoken = when (action) {
                "pause"    -> "Paused."
                "play"     -> "Resuming."
                "skip"     -> "Skipping."
                "previous" -> "Going back."
                "shuffle"  -> "Shuffling."
                "repeat"   -> "Repeat on."
                else       -> "Done."
            }
            ToolResult.Success(spoken, silent = true)
        } catch (e: Exception) {
            Log.e("MediaControlTool", "Failed to dispatch media key", e)
            ToolResult.Failure(FailurePhrases.MEDIA_CONTROL_FAILED)
        }
    }

    /**
     * Attempt to send the key event via the active MediaController.
     * Requires MEDIA_CONTENT_CONTROL permission to enumerate sessions;
     * if the permission is missing the SecurityException is caught and
     * we return false so the AudioManager fallback is used instead.
     */
    private fun tryDispatchViaMediaSession(keycode: Int): Boolean {
        return try {
            val msm = context.getSystemService(MediaSessionManager::class.java) ?: return false

            // getActiveSessions() needs MEDIA_CONTENT_CONTROL — check first to avoid a
            // crash on devices that enforce it strictly, then skip gracefully if absent.
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.MEDIA_CONTENT_CONTROL
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) return false

            val controllers = msm.getActiveSessions(null)
            val active = controllers.firstOrNull() ?: return false

            val eventTime = SystemClock.uptimeMillis()
            active.dispatchMediaButtonEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keycode, 0))
            active.dispatchMediaButtonEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP,   keycode, 0))
            true
        } catch (e: SecurityException) {
            Log.d("MediaControlTool", "No MEDIA_CONTENT_CONTROL permission — using AudioManager fallback")
            false
        }
    }

    /** Inject media key events through AudioManager (works without special permissions). */
    private fun dispatchViaAudioManager(keycode: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val eventTime = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keycode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP,   keycode, 0))
    }
}
