package com.jarvis.assistant.camera

import android.content.Context
import java.io.File

/**
 * Creates and locates app-owned image files under filesDir/pictures (internal storage).
 * Not accessible to other apps or the user's gallery — Jarvis-private only.
 */
internal object CameraFileStore {

    fun createImageFile(context: Context): File {
        val dir = picturesDir(context)
        return File(dir, "jarvis_photo_${System.currentTimeMillis()}.jpg")
    }

    fun getLatestPhoto(context: Context): File? =
        picturesDir(context)
            .listFiles { f -> f.isFile && f.name.startsWith("jarvis_photo_") && f.name.endsWith(".jpg") }
            ?.maxByOrNull { it.lastModified() }

    private fun picturesDir(context: Context): File {
        val dir = File(context.filesDir, "pictures")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
