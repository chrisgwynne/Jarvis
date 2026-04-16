package com.jarvis.assistant.security

import android.content.Context
import android.net.Uri
import android.util.Log

object StoragePolicy {
    private const val TAG = "JarvisStorage"

    // Schemes allowed for image URL downloads
    private val ALLOWED_URL_SCHEMES = setOf("https")

    /**
     * Validate a URL before downloading image bytes from it.
     * Only HTTPS is accepted. Returns false for http, file://, data:, etc.
     */
    fun isSafeImageUrl(url: String): Boolean {
        return try {
            val scheme = Uri.parse(url).scheme?.lowercase()
            val allowed = scheme in ALLOWED_URL_SCHEMES
            if (!allowed) Log.w(TAG, "Blocked unsafe image URL scheme='$scheme' url=${url.take(80)}")
            allowed
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse image URL: ${url.take(80)}")
            false
        }
    }

    /**
     * Returns true if [uri] is a safe destination for writing app-generated image output.
     * Approved destinations:
     *   - content://media/  (MediaStore)
     *   - app filesDir / cacheDir / externalFilesDir  (app-specific storage)
     */
    fun isApprovedImageWriteUri(context: Context, uri: Uri): Boolean {
        val uriString = uri.toString()
        val allowed = uriString.startsWith("content://media/") ||
                      uriString.startsWith(context.filesDir.absolutePath) ||
                      uriString.startsWith(context.cacheDir.absolutePath) ||
                      (context.getExternalFilesDir(null)?.absolutePath
                          ?.let { uriString.startsWith(it) } == true)
        logAccess("WRITE", uriString, allowed)
        return allowed
    }

    /**
     * Returns true if [uri] is a valid source for reading user images.
     * Approved sources:
     *   - content:// URIs (SAF picker, MediaStore, contacts)
     *   - app-owned file paths (filesDir, cacheDir)
     *
     * Raw /sdcard/ or /storage/emulated/0/ paths are NOT approved unless
     * they resolve to app-specific directories.
     */
    fun isValidImageReadUri(context: Context, uri: Uri): Boolean {
        val uriString = uri.toString()
        val allowed = uriString.startsWith("content://") ||
                      isAppOwnedPath(uriString, context)
        logAccess("READ", uriString, allowed)
        return allowed
    }

    /**
     * Returns true if [path] is inside an app-controlled directory.
     * Prevents direct access to /sdcard/, /Download/, /Documents/, etc.
     */
    fun isAppOwnedPath(path: String, context: Context): Boolean {
        val appRoots = listOfNotNull(
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath,
            context.externalCacheDir?.absolutePath
        )
        return appRoots.any { path.startsWith(it) }
    }

    private fun logAccess(operation: String, path: String, allowed: Boolean) {
        val truncated = path.take(100)
        if (allowed) {
            Log.d(TAG, "Storage $operation ALLOWED: $truncated")
        } else {
            Log.w(TAG, "Storage $operation BLOCKED: $truncated")
        }
    }
}
