package com.jarvis.assistant.vision

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ScreenshotContextManager — monitors the device media store for newly taken
 * screenshots and updates [VisualContextStore] so follow-up commands work
 * immediately after the user takes a screenshot.
 *
 * DETECTION:
 *   Registers a [ContentObserver] on [MediaStore.Images.Media.EXTERNAL_CONTENT_URI].
 *   Each new image is checked: if the file path contains "screenshot" (case-
 *   insensitive), it is treated as a new screenshot.
 *
 * PERMISSION:
 *   Requires READ_MEDIA_IMAGES (Android 13+) or READ_EXTERNAL_STORAGE (< 13).
 *   If the permission is missing the observer is registered but onChange()
 *   will receive no callbacks — silent no-op rather than a crash.
 *
 * LIFECYCLE:
 *   Call [attach] once when the service starts; call [detach] when it stops.
 *   Each attach/detach is idempotent.
 *
 * LOCAL-FIRST:
 *   Detection and storage are fully local.  No network call is made here;
 *   analysis happens only when the user explicitly asks ("analyse this
 *   screenshot") via [OcrScanTool] or [LookAtThisIntentHandler].
 */
class ScreenshotContextManager(
    private val context: Context,
    private val visualContextStore: VisualContextStore,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "ScreenshotCtxMgr"
    }

    private var observer: ContentObserver? = null

    /** Start watching for new screenshots. Safe to call multiple times. */
    fun attach() {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri ?: return
                scope.launch(Dispatchers.IO) { handleNewImage(uri) }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer!!
        )
        Log.d(TAG, "Attached screenshot observer")
    }

    /** Stop watching. Safe to call multiple times. */
    fun detach() {
        observer?.let {
            context.contentResolver.unregisterContentObserver(it)
            observer = null
            Log.d(TAG, "Detached screenshot observer")
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun handleNewImage(uri: Uri) {
        val path = resolveFilePath(context.contentResolver, uri) ?: return
        if (!path.contains("screenshot", ignoreCase = true)) return

        Log.d(TAG, "[SCREENSHOT_DETECTED] path=$path")
        visualContextStore.update(
            VisualContextStore.VisualContext(
                source        = VisualContextStore.Source.SCREENSHOT,
                imageFilePath = path,
                capturedAtMs  = System.currentTimeMillis(),
            )
        )
    }

    private fun resolveFilePath(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve URI $uri: ${e.message}")
            null
        }
    }
}
