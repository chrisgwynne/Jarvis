package com.jarvis.assistant.testing

import android.content.Context
import android.content.SharedPreferences
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Stub `Context.getSharedPreferences` on a Mockito mock so it returns
 * fresh [FakeSharedPreferences] instances keyed by name.  Reuses the same
 * fake for repeated lookups against the same prefs file.
 *
 * Use in `@Before` of any test that mocks an Android [Context] and whose
 * production code constructs a [SharedPreferences]-backed object (e.g.
 * `FlowStatePersistence`, `AppAliasStore`, `AliasLearningStore`).
 *
 * Example:
 * ```kotlin
 * @Before fun setUp() {
 *     context = mock()
 *     context.stubSharedPreferences()                 // ← one line
 *     // applicationContext used by AliasLearningStore et al.
 *     whenever(context.applicationContext).thenReturn(context)
 * }
 * ```
 */
fun Context.stubSharedPreferences(): Context {
    val prefsByName = mutableMapOf<String, SharedPreferences>()
    whenever(getSharedPreferences(any(), any())).thenAnswer { invocation ->
        val name = invocation.getArgument<String>(0)
        prefsByName.getOrPut(name) { FakeSharedPreferences() }
    }
    // Some callers route through applicationContext.
    whenever(applicationContext).thenReturn(this)
    return this
}

/**
 * Free-function form — useful when chaining onto Mockito's `mock<Context>()`.
 * Renamed from `stubSharedPreferences` (which would clash with the extension's
 * JVM signature) to keep both callable styles available.
 */
@JvmName("stubSharedPreferencesOn")
fun stubSharedPreferences(context: Context): Context = context.stubSharedPreferences()
