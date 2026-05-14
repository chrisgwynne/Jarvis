package com.jarvis.assistant.personality

import android.content.Context
import android.util.Log
import java.io.IOException

/**
 * PersonalityProfileLoader — reads the 10 personality markdown files
 * out of `app/src/main/assets/personality/` and exposes them as a
 * single immutable [PersonalityContext].
 *
 * The asset folder is the source of truth.  Editing the markdown is
 * the supported tuning path — no recompile required for content
 * changes (the strings ship in the APK; an OTA replace would update
 * them).
 *
 * **Fail-safe.** Missing files / IO errors degrade gracefully: the
 * affected section becomes an empty string, the rest still loads, and
 * the call site sees [PersonalityContext.has] = false for the missing
 * one.  The loader never throws to the runtime.
 *
 * Log markers:
 *   [PERSONALITY_LOAD_START]
 *   [PERSONALITY_LOAD_SUCCESS]   per file + final
 *   [PERSONALITY_LOAD_FAILED]    per file
 *   [PERSONALITY_PROFILE_ACTIVE] final summary
 */
class PersonalityProfileLoader(
    private val context: Context,
    /**
     * Debug builds re-read assets on every [load] call so editing
     * the markdown in Android Studio + running takes effect without
     * a process restart.  Release builds cache after first load.
     */
    private val cacheEnabled: Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0,
) {

    @Volatile private var cached: PersonalityContext? = null

    companion object {
        private const val TAG = "PersonalityLoader"
        private const val DIR = "personality"
    }

    /** Load (or return cached) personality context.  Never throws. */
    fun load(): PersonalityContext {
        cached?.takeIf { cacheEnabled }?.let { return it }
        Log.d(TAG, "[PERSONALITY_LOAD_START] cacheEnabled=$cacheEnabled")
        val map = mutableMapOf<PersonalitySection, String>()
        for (section in PersonalitySection.values()) {
            val text = readAsset(section.fileName)
            if (text != null) {
                map[section] = text
                Log.d(TAG, "[PERSONALITY_LOAD_SUCCESS] file=${section.fileName} chars=${text.length}")
            } else {
                Log.w(TAG, "[PERSONALITY_LOAD_FAILED] file=${section.fileName}")
            }
        }
        val ctx = PersonalityContext(map)
        cached = ctx
        Log.d(TAG, "[PERSONALITY_PROFILE_ACTIVE] " +
            "loaded=${ctx.loadedSections.size}/${PersonalitySection.values().size}")
        return ctx
    }

    /** Test seam — drop the cache so a debug rebuild is observable. */
    fun invalidate() { cached = null }

    private fun readAsset(name: String): String? = try {
        context.assets.open("$DIR/$name").bufferedReader().use { it.readText() }
    } catch (_: IOException) { null }
      catch (_: Exception)   { null }
}
