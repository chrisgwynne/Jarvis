package com.jarvis.assistant.audio.recording

import android.util.Log
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.NetworkClient
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * RecordingTranscriber — sends a saved audio file to OpenAI Whisper for transcription.
 *
 * PROVIDER SUPPORT (v1):
 *   OpenAI only — POST /audio/transcriptions with multipart/form-data.
 *   Other providers return a structured [LlmException] with a clear switch-to-OpenAI message.
 *
 * BLOCKING CALL:
 *   Uses OkHttp's synchronous execute() inside withContext(Dispatchers.IO) — safe and
 *   avoids allocating an extra callback object for a one-shot file upload.
 *
 * FUTURE EXTENSION:
 *   Groq's Whisper endpoint uses the same wire format with a different base URL.
 *   Add "Groq" to the when-branch and pass groqBaseUrl when it becomes relevant.
 */
class RecordingTranscriber(private val settings: SettingsStore) {

    companion object {
        private const val TAG           = "RecordingTranscriber"
        private const val WHISPER_URL   = "https://api.openai.com/v1/audio/transcriptions"
        private const val WHISPER_MODEL = "whisper-1"
    }

    /**
     * Transcribe [audioFile] to a plain-text string.
     * Throws [LlmException] if the provider is unsupported, the file is missing/empty,
     * or the API call fails.
     */
    suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
        if (settings.llmProvider != "OpenAI") {
            throw LlmException(
                "Transcription requires OpenAI. Current provider: ${settings.llmProvider}. " +
                "Switch to OpenAI in Settings to use this feature."
            )
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            throw LlmException("Recording file is empty or missing: ${audioFile.name}")
        }

        Log.d(TAG, "Transcribing ${audioFile.name} (${audioFile.length() / 1024} KB)")

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", WHISPER_MODEL)
            .addFormDataPart(
                name        = "file",
                filename    = audioFile.name,
                body        = audioFile.asRequestBody("audio/mpeg".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(WHISPER_URL)
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .post(requestBody)
            .build()

        // Blocking execute() is safe here — we're on Dispatchers.IO
        val response = NetworkClient.http.newCall(request).execute()
        response.use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw LlmException("Whisper API error ${r.code}: ${body.take(200)}")
            }
            val text = try {
                JSONObject(body).optString("text", "")
            } catch (e: JSONException) {
                throw LlmException("Whisper returned malformed JSON: ${body.take(200)}", e)
            }
            text.trim().ifBlank { throw LlmException("Whisper returned an empty transcript.") }
        }
    }
}
