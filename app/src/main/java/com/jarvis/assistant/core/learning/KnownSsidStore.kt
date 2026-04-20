package com.jarvis.assistant.core.learning

import android.content.Context
import android.content.SharedPreferences

/**
 * KnownSsidStore — durable set of Wi-Fi SSIDs the user has implicitly or
 * explicitly "tagged" by virtue of connecting to them more than once.
 *
 * Used by [com.jarvis.assistant.core.decisions.triggers.UnfamiliarSsidTrigger]
 * to distinguish "new network you should name" from "your normal Wi-Fi".
 *
 * Storage is SharedPreferences — tiny payload, single-user device, no need
 * for Room overhead.
 */
class KnownSsidStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isKnown(ssid: String): Boolean {
        val set = prefs.getStringSet(KEY_KNOWN, emptySet()) ?: emptySet()
        return ssid in set
    }

    /**
     * Record one observation of [ssid]. Once it has been observed
     * [promoteAfter] times it becomes "known" and [isKnown] returns true.
     * The seen-count is kept per-SSID so networks the user connects to
     * occasionally (friend's house) also get promoted naturally.
     */
    fun observe(ssid: String, promoteAfter: Int = 2) {
        val countKey = "$KEY_SEEN_PREFIX$ssid"
        val count = prefs.getInt(countKey, 0) + 1
        val editor = prefs.edit().putInt(countKey, count)
        if (count >= promoteAfter) {
            val known = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty().toMutableSet()
            known.add(ssid)
            editor.putStringSet(KEY_KNOWN, known)
        }
        editor.apply()
    }

    /** Mark [ssid] known explicitly (e.g. after the user names it). */
    fun tagKnown(ssid: String) {
        val known = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty().toMutableSet()
        known.add(ssid)
        prefs.edit().putStringSet(KEY_KNOWN, known).apply()
    }

    fun forget(ssid: String) {
        val known = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty().toMutableSet()
        known.remove(ssid)
        prefs.edit()
            .putStringSet(KEY_KNOWN, known)
            .remove("$KEY_SEEN_PREFIX$ssid")
            .apply()
    }

    fun allKnown(): Set<String> = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()

    companion object {
        private const val PREFS = "jarvis_known_ssids"
        private const val KEY_KNOWN = "known"
        private const val KEY_SEEN_PREFIX = "seen_"
    }
}
