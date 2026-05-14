package com.jarvis.assistant.testing

import android.content.SharedPreferences

/**
 * FakeSharedPreferences — in-memory implementation of [SharedPreferences].
 *
 * Replaces a real Android SharedPreferences in plain JVM unit tests where
 * Robolectric would be overkill.  Backed by a [MutableMap] so tests can
 * pre-populate values or assert state directly.
 *
 * Constraints:
 *  - Listeners are stored but never fired (none of the production code we
 *    test reads change notifications).
 *  - Type checks are loose — `getString` on an int returns null rather than
 *    throwing, matching Android's behaviour on type mismatch.
 */
class FakeSharedPreferences(
    private val store: MutableMap<String, Any?> = mutableMapOf()
) : SharedPreferences {

    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(store)

    override fun getString(key: String?, defValue: String?): String? =
        (store[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (store[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int =
        (store[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long =
        (store[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float =
        (store[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (store[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) { listener?.let { listeners += it } }
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) { listeners.remove(listener) }

    inner class Editor : SharedPreferences.Editor {
        private val pending  = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear    = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = values }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = value }
        override fun remove(key: String?): SharedPreferences.Editor =
            apply { if (key != null) removals += key }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) store.clear()
            for (k in removals) store.remove(k)
            store.putAll(pending)
            pending.clear()
            removals.clear()
            clear = false
        }
    }
}
