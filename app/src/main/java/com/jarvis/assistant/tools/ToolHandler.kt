package com.jarvis.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager as SysAudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.telephony.SmsManager
import android.util.Log
import com.jarvis.assistant.util.SettingsStore

/**
 * ToolHandler — detects actionable intents in the user's transcript and either
 * executes them directly (phone/device actions) or augments the prompt with
 * real-time data (web search).
 *
 * RESULT TYPES:
 *   Executed   — a device action ran; [feedback] is what Jarvis should say.
 *   Augmented  — [newTranscript] has search context prepended; pass to LlmRouter.
 *   PassThrough — no tool needed; use the original transcript as-is.
 *
 * SUPPORTED ACTIONS:
 *   "call [name]"                              → phone call
 *   "text / message [name] [content]"          → SMS
 *   "whatsapp [name] and tell them [message]"  → WhatsApp deep link
 *   "open [app name]"                          → launch app
 *   "turn up / lower the volume"               → AudioManager
 *   "mute the phone"                           → AudioManager mute
 *   "turn on / off the flashlight"             → CameraManager torch
 *   "set an alarm for 7am"                     → AlarmClock intent
 *   "set a timer for 10 minutes"               → AlarmClock timer intent
 *   Any live-data query                        → web search context injected
 */
class ToolHandler(
    private val context: Context,
    private val settings: SettingsStore
) {
    companion object {
        private const val TAG = "ToolHandler"

        // ── Phone actions ─────────────────────────────────────────────────────
        private val CALL_RE = Regex(
            """(?:call|phone|ring|dial)\s+(.+?)(?:\s+(?:for me|please|now))?$""",
            RegexOption.IGNORE_CASE
        )
        private val TEXT_RE = Regex(
            """(?:text|message|send (?:a )?(?:text|message)(?: to)?)\s+(.+?)(?:\s+(?:saying|and say|to say|that)\s+(.+))?$""",
            RegexOption.IGNORE_CASE
        )

        // ── WhatsApp ──────────────────────────────────────────────────────────
        private val WHATSAPP_RE = Regex(
            """(?:whatsapp|whats\s*app|wa)\s+(.+?)\s+(?:and\s+)?(?:tell(?:\s+them)?|say(?:ing)?|send(?:ing)?|message)\s+(.+)""",
            RegexOption.IGNORE_CASE
        )

        // ── App launch ────────────────────────────────────────────────────────
        private val OPEN_RE = Regex("""(?:open|launch|start)\s+(.+)""", RegexOption.IGNORE_CASE)

        // ── Volume ────────────────────────────────────────────────────────────
        private val VOLUME_UP_RE   = Regex(
            """(?:turn\s+up|raise|increase|louder)\s*(?:the\s+)?volume|volume\s+up""",
            RegexOption.IGNORE_CASE
        )
        private val VOLUME_DOWN_RE = Regex(
            """(?:turn\s+down|lower|decrease|quieter)\s*(?:the\s+)?volume|volume\s+down""",
            RegexOption.IGNORE_CASE
        )
        private val VOLUME_MUTE_RE = Regex(
            """(?:mute|silence)\s+(?:the\s+)?(?:phone|sound|volume|ringer)|mute\s+(?:me|my\s+phone)""",
            RegexOption.IGNORE_CASE
        )

        // ── Flashlight ────────────────────────────────────────────────────────
        private val TORCH_ON_RE  = Regex(
            """(?:turn|switch|put)\s+on\s+(?:the\s+)?(?:flashlight|torch|light)|(?:flashlight|torch)\s+on""",
            RegexOption.IGNORE_CASE
        )
        private val TORCH_OFF_RE = Regex(
            """(?:turn|switch|put)\s+off\s+(?:the\s+)?(?:flashlight|torch|light)|(?:flashlight|torch)\s+off""",
            RegexOption.IGNORE_CASE
        )

        // ── Alarm / Timer ─────────────────────────────────────────────────────
        private val ALARM_RE = Regex(
            """(?:set|create|add)\s+(?:an?\s+)?alarm(?:\s+(?:for|at)\s+(.+))?""",
            RegexOption.IGNORE_CASE
        )
        private val TIMER_RE = Regex(
            """(?:set|start|create)\s+(?:a\s+)?timer\s+(?:for\s+)?(.+)""",
            RegexOption.IGNORE_CASE
        )

        // ── Web search ────────────────────────────────────────────────────────
        private val SEARCH_TRIGGERS = setOf(
            "news", "latest",
            "weather", "forecast", "temperature", "score", "result",
            "stock price", "share price", "bitcoin", "crypto", "market",
            "who won", "who is", "what is", "when did", "where is",
            "search for", "look up", "find out", "tell me about"
        )
    }

    sealed class Result {
        data class Executed(val feedback: String) : Result()
        data class Augmented(val newTranscript: String) : Result()
        object PassThrough : Result()
    }

    private val contactLookup = ContactLookup(context)
    private val webSearch     = WebSearch()

    // ── Public entry point ────────────────────────────────────────────────────

    suspend fun handle(transcript: String): Result {
        val trimmed = transcript.trim()

        // Phone actions — highest priority
        CALL_RE.find(trimmed)?.let { return handleCall(it.groupValues[1].trim()) }
        TEXT_RE.find(trimmed)?.let { m ->
            return handleSms(m.groupValues[1].trim(), m.groupValues.getOrElse(2) { "" }.trim())
        }
        WHATSAPP_RE.find(trimmed)?.let { m ->
            return handleWhatsApp(m.groupValues[1].trim(), m.groupValues[2].trim())
        }
        OPEN_RE.find(trimmed)?.let { return handleOpenApp(it.groupValues[1].trim()) }

        // Device controls
        if (VOLUME_UP_RE.containsMatchIn(trimmed))   return handleVolume(+1)
        if (VOLUME_DOWN_RE.containsMatchIn(trimmed)) return handleVolume(-1)
        if (VOLUME_MUTE_RE.containsMatchIn(trimmed)) return handleVolume(0)
        if (TORCH_ON_RE.containsMatchIn(trimmed))    return handleTorch(true)
        if (TORCH_OFF_RE.containsMatchIn(trimmed))   return handleTorch(false)

        ALARM_RE.find(trimmed)?.let { m ->
            return handleAlarm(m.groupValues.getOrElse(1) { "" }.trim())
        }
        TIMER_RE.find(trimmed)?.let { m ->
            return handleTimer(m.groupValues[1].trim())
        }

        // Web search augmentation
        val lower = trimmed.lowercase()
        if (SEARCH_TRIGGERS.any { lower.contains(it) }) {
            val searchCtx = webSearch.search(trimmed, settings.braveSearchApiKey)
            if (searchCtx.isNotBlank()) {
                return Result.Augmented("$searchCtx\n\nUser asked: $trimmed")
            }
        }

        return Result.PassThrough
    }

    // ── Phone call ────────────────────────────────────────────────────────────

    private fun handleCall(name: String): Result {
        val contact = contactLookup.find(name)
            ?: return Result.Executed("I couldn't find $name in your contacts.")
        return try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.number}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.d(TAG, "Calling ${contact.displayName} at ${contact.number}")
            Result.Executed("Calling ${contact.displayName}.")
        } catch (e: SecurityException) {
            Result.Executed("I don't have permission to make calls. Grant the Call Phone permission in Settings.")
        } catch (e: Exception) {
            Result.Executed("Failed to place the call: ${e.message}")
        }
    }

    // ── SMS ───────────────────────────────────────────────────────────────────

    private fun handleSms(name: String, message: String): Result {
        val contact = contactLookup.find(name)
            ?: return Result.Executed("I couldn't find $name in your contacts.")

        if (message.isBlank()) {
            return try {
                context.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.number}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Result.Executed("Opening a message to ${contact.displayName}.")
            } catch (e: Exception) {
                Result.Executed("Couldn't open Messages: ${e.message}")
            }
        }

        return try {
            @Suppress("DEPRECATION")
            val sms = SmsManager.getDefault()
            sms.sendMultipartTextMessage(contact.number, null, sms.divideMessage(message), null, null)
            Log.d(TAG, "SMS sent to ${contact.displayName}")
            Result.Executed("Message sent to ${contact.displayName}.")
        } catch (e: SecurityException) {
            Result.Executed("I don't have permission to send messages. Grant the Send SMS permission in Settings.")
        } catch (e: Exception) {
            Result.Executed("Failed to send the message: ${e.message}")
        }
    }

    // ── WhatsApp ──────────────────────────────────────────────────────────────

    private fun handleWhatsApp(name: String, message: String): Result {
        val contact = contactLookup.find(name)
            ?: return Result.Executed("I couldn't find $name in your contacts.")

        // wa.me uses international format without leading +
        val cleanNumber = contact.number.replace(Regex("[^\\d+]"), "").trimStart('+')
        val url = "https://wa.me/$cleanNumber?text=${Uri.encode(message)}"

        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.d(TAG, "WhatsApp to ${contact.displayName}: $message")
            Result.Executed("Opening WhatsApp to message ${contact.displayName}.")
        } catch (e: Exception) {
            Result.Executed("Couldn't open WhatsApp: ${e.message}")
        }
    }

    // ── Open app ──────────────────────────────────────────────────────────────

    private fun handleOpenApp(appName: String): Result {
        val pm = context.packageManager
        val match = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.GET_META_DATA
        ).firstOrNull { it.loadLabel(pm).toString().contains(appName, ignoreCase = true) }
            ?: return Result.Executed("I couldn't find an app called $appName on your phone.")

        return try {
            val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return Result.Executed("Couldn't launch ${match.loadLabel(pm)}.")
            context.startActivity(intent)
            Result.Executed("Opening ${match.loadLabel(pm)}.")
        } catch (e: Exception) {
            Result.Executed("Failed to open the app: ${e.message}")
        }
    }

    // ── Volume ────────────────────────────────────────────────────────────────

    private fun handleVolume(direction: Int): Result {
        val am = context.getSystemService(SysAudioManager::class.java)!!
        return when {
            direction > 0 -> {
                am.adjustStreamVolume(SysAudioManager.STREAM_MUSIC, SysAudioManager.ADJUST_RAISE, SysAudioManager.FLAG_SHOW_UI)
                am.adjustStreamVolume(SysAudioManager.STREAM_RING,  SysAudioManager.ADJUST_RAISE, 0)
                Result.Executed("Volume raised.")
            }
            direction < 0 -> {
                am.adjustStreamVolume(SysAudioManager.STREAM_MUSIC, SysAudioManager.ADJUST_LOWER, SysAudioManager.FLAG_SHOW_UI)
                am.adjustStreamVolume(SysAudioManager.STREAM_RING,  SysAudioManager.ADJUST_LOWER, 0)
                Result.Executed("Volume lowered.")
            }
            else -> {
                am.adjustStreamVolume(SysAudioManager.STREAM_RING, SysAudioManager.ADJUST_MUTE, SysAudioManager.FLAG_SHOW_UI)
                Result.Executed("Phone muted.")
            }
        }
    }

    // ── Flashlight ────────────────────────────────────────────────────────────

    private fun handleTorch(on: Boolean): Result {
        return try {
            val cm       = context.getSystemService(CameraManager::class.java)!!
            val cameraId = cm.cameraIdList.firstOrNull()
                ?: return Result.Executed("No camera available for flashlight.")
            cm.setTorchMode(cameraId, on)
            Result.Executed(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            Result.Executed("Couldn't toggle flashlight: ${e.message}")
        }
    }

    // ── Alarm ─────────────────────────────────────────────────────────────────

    private fun handleAlarm(timeStr: String): Result {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val parsed = parseTime(timeStr)
            if (parsed != null) {
                intent.putExtra(AlarmClock.EXTRA_HOUR, parsed.first)
                intent.putExtra(AlarmClock.EXTRA_MINUTES, parsed.second)
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            context.startActivity(intent)
            Result.Executed(if (timeStr.isBlank()) "Setting an alarm." else "Alarm set for $timeStr.")
        } catch (e: Exception) {
            Result.Executed("Couldn't set alarm: ${e.message}")
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun handleTimer(desc: String): Result {
        return try {
            val seconds = parseTimerDuration(desc)
            val intent  = Intent(AlarmClock.ACTION_SET_TIMER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (seconds > 0) {
                intent.putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            context.startActivity(intent)
            Result.Executed(if (seconds > 0) "Timer set for $desc." else "Opening timer.")
        } catch (e: Exception) {
            Result.Executed("Couldn't set timer: ${e.message}")
        }
    }

    // ── Time parsing helpers ──────────────────────────────────────────────────

    /** Parse "7:30 am", "7:30am", "7am", "7 am" → Pair(hour24, minutes). */
    private fun parseTime(s: String): Pair<Int, Int>? {
        val clean = s.trim().lowercase()
        // "7:30 am" / "7:30am"
        Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?""").find(clean)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (m.groupValues[3] == "pm" && h < 12) h += 12
            if (m.groupValues[3] == "am" && h == 12) h = 0
            return Pair(h, min)
        }
        // "7am" / "7 am"
        Regex("""(\d{1,2})\s*(am|pm)""").find(clean)?.let { m ->
            var h = m.groupValues[1].toInt()
            if (m.groupValues[2] == "pm" && h < 12) h += 12
            if (m.groupValues[2] == "am" && h == 12) h = 0
            return Pair(h, 0)
        }
        return null
    }

    /** Parse "10 minutes", "1 hour 30 minutes", "90 seconds" → total seconds. */
    private fun parseTimerDuration(s: String): Int {
        val clean = s.trim().lowercase()
        var total = 0
        Regex("""(\d+)\s*hour""").find(clean)?.let  { total += it.groupValues[1].toInt() * 3600 }
        Regex("""(\d+)\s*min""").find(clean)?.let   { total += it.groupValues[1].toInt() * 60 }
        Regex("""(\d+)\s*sec""").find(clean)?.let   { total += it.groupValues[1].toInt() }
        // bare number → assume minutes
        if (total == 0) Regex("""^(\d+)$""").find(clean.trim())?.let { total = it.groupValues[1].toInt() * 60 }
        return total
    }
}
