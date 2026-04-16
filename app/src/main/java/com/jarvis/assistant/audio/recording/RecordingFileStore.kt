package com.jarvis.assistant.audio.recording

import android.content.Context
import java.io.File

/**
 * Manages app-owned audio recording files under getExternalFilesDir("Recordings").
 * Falls back to filesDir/recordings if external storage is unavailable.
 *
 * NO WRITE_EXTERNAL_STORAGE permission required — app-specific directories
 * are fully accessible without storage permission on all API levels.
 */
object RecordingFileStore {

    private const val DIR_NAME = "Recordings"

    /** Creates a new uniquely named M4A file ready for MediaRecorder output. */
    fun createRecordingFile(context: Context): File {
        val dir = storageDir(context)
        return File(dir, "jarvis_rec_${System.currentTimeMillis()}.m4a")
    }

    /**
     * Returns the most recently modified non-empty recording file,
     * or null if no recordings exist.
     */
    fun getLatestRecording(context: Context): File? =
        storageDir(context)
            .listFiles { f -> f.isFile && f.name.startsWith("jarvis_rec_") && f.length() > 0L }
            ?.maxByOrNull { it.lastModified() }

    private fun storageDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
