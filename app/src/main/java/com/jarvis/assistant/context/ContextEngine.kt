package com.jarvis.assistant.context

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.jarvis.assistant.location.CurrentLocationProvider
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ContextEngine — builds a fresh [DeviceContext] snapshot on every call.
 *
 * WHY FRESH EVERY CALL?
 *   The system prompt is rebuilt before every LLM inference, so the time,
 *   battery level, and audio route always reflect the current moment.
 *   All reads are lightweight (system service calls + clock) — no I/O.
 *
 * USAGE:
 *   val ctx = contextEngine.build()
 *   val prompt = contextEngine.toPromptFragment(ctx)
 */
class ContextEngine(
    private val context: Context,
    private val locationProvider: CurrentLocationProvider? = null,
) {

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy")
        private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a")
        /**
         * Context reads (battery intent, connectivity query, audio route) are
         * cheap individually but add up on bursty paths that call build()
         * multiple times per turn.  Cache for a short window — the only
         * time-sensitive field is the minute-granular clock string.
         */
        private const val CACHE_TTL_MS = 2_000L
    }

    @Volatile private var cached: DeviceContext? = null
    @Volatile private var cachedAt: Long = 0L

    fun build(): DeviceContext {
        val now = System.currentTimeMillis()
        val snapshot = cached
        if (snapshot != null && now - cachedAt < CACHE_TTL_MS) return snapshot
        val fresh = buildFresh()
        cached = fresh
        cachedAt = now
        return fresh
    }

    /** Force a rebuild on the next [build] call. */
    fun invalidate() { cachedAt = 0L }

    private fun buildFresh(): DeviceContext {
        val now      = LocalDateTime.now()
        val battery  = getBattery()
        return DeviceContext(
            date             = now.format(DATE_FMT),
            time             = now.format(TIME_FMT),
            timezone         = ZoneId.systemDefault().id,
            batteryPercent   = battery.first,
            isCharging       = battery.second,
            deviceModel      = Build.MODEL,
            isOnline         = isOnline(),
            audioRoute       = getAudioRoute(),
            headsetConnected = isHeadsetConnected(),
            location         = locationProvider?.lastResult?.displayLabel
        )
    }

    /**
     * One-liner fragment injected into every system prompt.
     *
     * [presence] is optional — callers that don't track it (tests, legacy
     * flows) can omit it and the fragment stays unchanged.  When supplied it
     * appends a brief presence note so the LLM has continuity cues across
     * turns (time phase, whether the user is mid-conversation, etc.).
     */
    fun toPromptFragment(ctx: DeviceContext, presence: Presence? = null): String = buildString {
        append("Today is ${ctx.date}. The time is ${ctx.time} (${ctx.timezone}). ")
        append("Battery: ${ctx.batteryPercent}%")
        if (ctx.isCharging) append(" charging")
        append(". Device: ${ctx.deviceModel}. ")
        if (ctx.location != null) append("User location: ${ctx.location}. ")
        if (ctx.headsetConnected) append("User is wearing headphones. ")
        if (!ctx.isOnline) append("DEVICE IS OFFLINE — no internet. Only local commands work. ")
        if (presence != null) append(presence.toPromptFragment()).append(' ')
    }

    /** Expose the online state cheaply for the tool router. */
    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun getBattery(): Pair<Int, Boolean> {
        val bm  = context.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        val intent  = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status  = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(pct, charging)
    }

    @Suppress("DEPRECATION")
    private fun getAudioRoute(): AudioRoute {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return AudioRoute.UNKNOWN
        return when {
            am.isBluetoothScoOn || am.isBluetoothA2dpOn -> AudioRoute.BLUETOOTH_HEADSET
            am.isWiredHeadsetOn                         -> AudioRoute.WIRED_HEADSET
            am.isSpeakerphoneOn                         -> AudioRoute.SPEAKER
            else                                        -> AudioRoute.EARPIECE
        }
    }

    @Suppress("DEPRECATION")
    private fun isHeadsetConnected(): Boolean {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return false
        return am.isWiredHeadsetOn || am.isBluetoothA2dpOn || am.isBluetoothScoOn
    }
}
