package com.jarvis.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ScreenshotCaptureService — captures the current foreground screen through
 * the running [JarvisAccessibilityService] and writes a timestamped PNG into
 * app-private storage.
 *
 * WHY ACCESSIBILITY (and not MediaProjection)?
 *   Accessibility's takeScreenshot API (API 30+) does not trigger the system
 *   "Your screen will be recorded" consent dialog every session.  The user
 *   has already granted accessibility access once — that's enough.
 *
 * STORAGE LAYOUT:
 *   filesDir/screen_observations/<capturedAtMs>.png
 *   No external storage, no MediaStore — the images never leave the app
 *   sandbox and are wiped with app data.
 *
 * FAILURE MODES:
 *   * Accessibility not connected → returns [Result.Unavailable].
 *   * Snapshot returns no PNG payload (API < 30, OEM quirks) → returns
 *     [Result.Unavailable] without writing anything.
 *   * Disk write fails → returns [Result.Failure].
 */
class ScreenshotCaptureService(private val context: Context) {

    sealed class Result {
        /** Capture succeeded; PNG is on disk at [file]. */
        data class Success(
            val file: File,
            val snapshot: ScreenSnapshot
        ) : Result()

        /** Accessibility service not running, or OS returned no bitmap. */
        data class Unavailable(val reason: String) : Result()

        /** Capture worked but the file write blew up. */
        data class Failure(val reason: String, val cause: Throwable? = null) : Result()
    }

    companion object {
        private const val TAG = "ScreenshotCapture"
        private const val DIR = "screen_observations"
        private const val PNG_QUALITY = 90
    }

    /**
     * Capture one frame.  Pure suspend function — no main-thread work, no
     * callbacks.  Returns one of the three [Result] variants.
     */
    suspend fun capture(): Result = withContext(Dispatchers.IO) {
        if (!JarvisAccessibilityService.isConnected()) {
            Log.w(TAG, "Accessibility service not connected — cannot capture")
            return@withContext Result.Unavailable("Accessibility service not enabled")
        }

        val snapshot = JarvisAccessibilityService.snapshot(withScreenshot = true)
            ?: return@withContext Result.Unavailable("Snapshot failed")

        val base64 = snapshot.screenshotPngBase64
        if (base64.isNullOrBlank()) {
            Log.w(TAG, "Snapshot carried no PNG (API<30 or OEM blocked)")
            return@withContext Result.Unavailable("Screenshot not available on this device")
        }

        val outDir = File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }
        val outFile = File(outDir, "${snapshot.capturedAtMs}.png")

        return@withContext try {
            writePng(base64, outFile)
            Log.d(TAG, "Wrote ${outFile.name} (${outFile.length() / 1024} KB)")
            Result.Success(file = outFile, snapshot = snapshot)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write screenshot to ${outFile.absolutePath}", e)
            Result.Failure(reason = "Could not save screenshot", cause = e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Snapshot payload was not valid base64", e)
            Result.Failure(reason = "Corrupt screenshot payload", cause = e)
        }
    }

    /**
     * Decode the base64 PNG, re-encode as PNG at [PNG_QUALITY], and write to
     * disk.  Re-encoding normalises the payload (the Accessibility API's
     * output format is technically unspecified) and gives us one place to
     * control file size.
     */
    private fun writePng(base64Png: String, outFile: File) {
        val bytes = Base64.decode(base64Png, Base64.NO_WRAP)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("Bitmap decode returned null")
        try {
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
