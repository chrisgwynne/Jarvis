package com.jarvis.assistant.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.llm.LlmException
import com.jarvis.assistant.llm.NetworkClient
import com.jarvis.assistant.util.SettingsStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * VisionClient — sends a captured image to the active LLM provider's vision endpoint.
 *
 * PROVIDER SUPPORT (v1):
 *   OpenAI    → gpt-4o  (POST /chat/completions, data URI in content array)
 *   OpenRouter → openai/gpt-4o  (same wire format, different base URL)
 *   Anthropic  → claude-haiku-4-5-20251001  (Anthropic vision content block format)
 *   Others     → LlmException with a clear "switch to OpenAI/Anthropic" message
 *
 * IMAGE HANDLING:
 *   Scales down to ≤ MAX_SIDE_PX on the long side BEFORE encoding to JPEG.
 *   Uses BitmapFactory inSampleSize — never loads full-res bitmap into memory.
 *   Final payload is typically 30–80 KB of base64 (~40–100 KB JSON overhead).
 *
 * JSON PARSING:
 *   Uses org.json.JSONObject (available in every Android API level) to avoid
 *   Gson local-class obfuscation issues with R8/ProGuard.
 */
class VisionClient(private val settings: SettingsStore) {

    companion object {
        private const val TAG        = "VisionClient"
        private const val MAX_SIDE   = 1024     // px — long side cap before encoding
        private const val JPEG_Q     = 80       // JPEG quality (0-100)
        private const val MAX_TOKENS = 200
    }

    /**
     * Capture-and-analyze: encode [imageFile] and send to the active provider's
     * vision endpoint with [prompt].  Returns the model's text answer.
     * Throws [LlmException] if the provider doesn't support vision or the call fails.
     */
    suspend fun analyze(imageFile: File, prompt: String): String {
        val base64 = encodeImageFile(imageFile)   // scales + compresses on calling thread (IO)
        Log.d(TAG, "Sending vision request to ${settings.llmProvider} " +
                   "(image=${(base64.length * 3 / 4 / 1024)}KB base64)")
        return when (settings.llmProvider) {
            "OpenAI"     -> openAiVision(base64, prompt, "https://api.openai.com/v1",        "gpt-4o")
            "OpenRouter" -> openAiVision(base64, prompt, "https://openrouter.ai/api/v1",     "openai/gpt-4o")
            "Anthropic"  -> anthropicVision(base64, prompt)
            else -> throw LlmException(
                "Image analysis requires OpenAI, OpenRouter, or Anthropic. " +
                "Current provider: ${settings.llmProvider}. Switch providers in Settings."
            )
        }
    }

    // ── OpenAI-compatible vision ───────────────────────────────────────────────

    private suspend fun openAiVision(
        base64: String,
        prompt: String,
        baseUrl: String,
        model: String
    ): String {
        // Escape prompt as a JSON string value (handles quotes, newlines, etc.)
        val promptJson = NetworkClient.gson.toJson(prompt)

        val body = """{"model":"$model","max_tokens":$MAX_TOKENS,"messages":[{"role":"user","content":[""" +
                   """{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$base64"}},""" +
                   """{"type":"text","text":$promptJson}]}]}"""

        val response = NetworkClient.post(
            url     = "$baseUrl/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer ${settings.apiKey}",
                "Content-Type"  to "application/json"
            ),
            body = body
        )

        return JSONObject(response)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
            .ifBlank { throw LlmException("Vision API returned an empty response.") }
    }

    // ── Anthropic vision ──────────────────────────────────────────────────────

    private suspend fun anthropicVision(base64: String, prompt: String): String {
        val promptJson = NetworkClient.gson.toJson(prompt)

        val body = """{"model":"claude-haiku-4-5-20251001","max_tokens":$MAX_TOKENS,"messages":[{"role":"user","content":[""" +
                   """{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"$base64"}},""" +
                   """{"type":"text","text":$promptJson}]}]}"""

        val response = NetworkClient.post(
            url     = "https://api.anthropic.com/v1/messages",
            headers = mapOf(
                "x-api-key"         to settings.apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type"      to "application/json"
            ),
            body = body
        )

        return JSONObject(response)
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
            .trim()
            .ifBlank { throw LlmException("Anthropic vision returned an empty response.") }
    }

    // ── Image encoding ────────────────────────────────────────────────────────

    /**
     * Encode [file] to a base64 JPEG string (no data-URI prefix) for use in a
     * [com.jarvis.assistant.llm.Message.imageBase64] field when routing through
     * the main LLM pipeline.
     */
    fun encodeImageForPipeline(file: File): String = encodeImageFile(file)

    /**
     * Two-pass decode:
     *   Pass 1: read only image dimensions (no pixel data loaded).
     *   Pass 2: decode at down-sampled size using inSampleSize (power-of-2 scale).
     * Re-compress the down-sampled bitmap to JPEG at [JPEG_Q] quality.
     * Uses RGB_565 config (2 bytes/px vs 4 for ARGB_8888) to halve heap pressure.
     */
    private fun encodeImageFile(file: File): String {
        // Pass 1 — dimensions only
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)

        val sample = computeSampleSize(boundsOpts.outWidth, boundsOpts.outHeight)

        // Pass 2 — down-sampled decode
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize      = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            ?: throw LlmException("Could not decode captured image for analysis.")

        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Returns the smallest power-of-2 sample size so the decoded image fits within MAX_SIDE. */
    private fun computeSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) > MAX_SIDE) sample *= 2
        return sample
    }
}
