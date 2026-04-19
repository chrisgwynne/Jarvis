package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.SharedPreferences

/**
 * AppAliasStore — persistent map of spoken app aliases → package names.
 *
 * Separate from [SettingsStore] because aliases are a per-device learned list
 * (not user-facing configuration) and benefit from a cleaner API surface.
 *
 * Backed by SharedPreferences ("jarvis_app_aliases"), one key per alias.
 * Lookups are case-insensitive; the raw spoken form is normalized before
 * read/write so "Spotify", "spotify", and "  Spotify " all resolve to the
 * same package.
 */
class AppAliasStore(context: Context) {

    companion object {
        private const val PREFS = "jarvis_app_aliases"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Return the package name previously learned for [spokenAlias], if any. */
    fun get(spokenAlias: String): String? {
        val key = normalize(spokenAlias)
        if (key.isEmpty()) return null
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * Persist that [spokenAlias] resolves to [packageName].  Callers should only
     * do this after a successful launch (alias confirmed by actually starting
     * the app) so misheard words don't pollute the store.
     *
     * [durable] — when true, uses commit() instead of apply().  Use on the
     * post-confirmation path where a quick process death (OS restart after
     * app launch, task kill) could otherwise lose the write.
     */
    fun put(spokenAlias: String, packageName: String, durable: Boolean = false) {
        val key = normalize(spokenAlias)
        if (key.isEmpty() || packageName.isBlank()) return
        val editor = prefs.edit().putString(key, packageName)
        if (durable) editor.commit() else editor.apply()
    }

    /** Remove an alias (used when an app is uninstalled or the user corrects it). */
    fun remove(spokenAlias: String) {
        val key = normalize(spokenAlias)
        if (key.isEmpty()) return
        prefs.edit().remove(key).apply()
    }

    /** Snapshot of all known aliases — useful for debug UI. */
    val all: Map<String, String>
        @Suppress("UNCHECKED_CAST")
        get() = prefs.all.filterValues { it is String } as Map<String, String>

    private fun normalize(alias: String): String =
        alias.trim().lowercase()
}
