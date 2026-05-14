package com.jarvis.assistant.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FeatureFlagStore — persistent overrides for [VoiceFeatureFlags].
 *
 * # Why SharedPreferences (not DataStore)
 *
 * The rest of the app already uses [android.content.SharedPreferences]
 * everywhere (see [com.jarvis.assistant.tools.device.AppAliasStore],
 * [com.jarvis.assistant.voice.learning.AliasLearningStore], the
 * notification listener cache, etc.).  Adding `androidx.datastore` for one
 * feature would be a new transitive dependency for zero functional gain.
 * Reads from SharedPreferences are thread-safe; writes via `apply()` are
 * non-blocking.
 *
 * # Override model
 *
 *  - `getOverride(flag) == null`  → use [VoiceFeatureFlags.Flag.defaultEnabled]
 *  - `getOverride(flag) == true`  → flag is on
 *  - `getOverride(flag) == false` → flag is off
 *
 * After [loadAtStartup] runs, every persisted override is mirrored into the
 * in-memory `VoiceFeatureFlags.overrides` map so the existing
 * [VoiceFeatureFlags.isEnabled] readers see them with no API change.
 *
 * Subsequent writes via [setOverride] / [clearOverride] update both layers
 * in one place so the two never drift.
 *
 * # Flow exposure
 *
 * [overridesFlow] emits the latest override snapshot for the Settings UI to
 * re-render against.  It does not emit on a missing-override read — only
 * when the user sets / clears something.
 */
class FeatureFlagStore(context: Context) {

    companion object {
        private const val TAG       = "FeatureFlagStore"
        private const val PREFS     = "jarvis_feature_flags"
        /** Marker stored in prefs when an override is intentionally null/cleared. */
        private const val UNSET     = "unset"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _overridesFlow = MutableStateFlow(currentOverridesSnapshot())
    val overridesFlow: StateFlow<Map<String, Boolean?>> = _overridesFlow.asStateFlow()

    /**
     * Read every persisted override and apply it to [VoiceFeatureFlags]'s
     * in-memory map.  Call once from [com.jarvis.assistant.JarvisApp.onCreate]
     * — must run before any subsystem reads a flag.
     */
    fun loadAtStartup() {
        var loaded = 0
        for (flag in VoiceFeatureFlags.Flag.values()) {
            val override = getOverride(flag) ?: continue
            VoiceFeatureFlags.setOverride(flag, override)
            loaded++
        }
        Log.i(TAG, "[FEATURE_FLAG_OVERRIDE_LOADED] count=$loaded active=$loaded " +
            "snapshot=${VoiceFeatureFlags.snapshot()}")
        _overridesFlow.value = currentOverridesSnapshot()
    }

    /** Return the persisted override for [flag], or null when none set. */
    fun getOverride(flag: VoiceFeatureFlags.Flag): Boolean? {
        val raw = prefs.getString(flag.key, UNSET) ?: UNSET
        return when (raw) {
            "true"  -> true
            "false" -> false
            else    -> null
        }
    }

    /**
     * Persist an override for [flag] and mirror into [VoiceFeatureFlags].
     * Passing null is equivalent to [clearOverride].
     */
    fun setOverride(flag: VoiceFeatureFlags.Flag, enabled: Boolean?) {
        if (enabled == null) { clearOverride(flag); return }
        prefs.edit().putString(flag.key, enabled.toString()).apply()
        VoiceFeatureFlags.setOverride(flag, enabled)
        _overridesFlow.value = currentOverridesSnapshot()
        Log.d(TAG, "[FEATURE_FLAG_OVERRIDE_SET] ${flag.name}=$enabled (default=${flag.defaultEnabled})")
    }

    /** Remove any persisted override, restoring the flag's default. */
    fun clearOverride(flag: VoiceFeatureFlags.Flag) {
        prefs.edit().remove(flag.key).apply()
        VoiceFeatureFlags.clearOverride(flag)
        _overridesFlow.value = currentOverridesSnapshot()
        Log.d(TAG, "[FEATURE_FLAG_OVERRIDE_CLEARED] ${flag.name} (default=${flag.defaultEnabled})")
    }

    /** True iff the user has set an explicit override. */
    fun hasOverride(flag: VoiceFeatureFlags.Flag): Boolean =
        prefs.contains(flag.key)

    /** Effective value: override if present, else default. */
    fun effectiveValue(flag: VoiceFeatureFlags.Flag): Boolean =
        getOverride(flag) ?: flag.defaultEnabled

    /** For tests / debug. */
    fun clearAll() {
        prefs.edit().clear().apply()
        for (flag in VoiceFeatureFlags.Flag.values()) VoiceFeatureFlags.clearOverride(flag)
        _overridesFlow.value = currentOverridesSnapshot()
    }

    private fun currentOverridesSnapshot(): Map<String, Boolean?> =
        VoiceFeatureFlags.Flag.values().associate { it.name to getOverride(it) }
}
