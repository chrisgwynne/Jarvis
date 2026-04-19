package com.jarvis.assistant.tools.device

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.jarvis.assistant.llm.NetworkClient
import com.jarvis.assistant.security.StoragePolicy
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import com.jarvis.assistant.util.SettingsStore
import org.json.JSONObject
import java.io.InputStream
import java.net.URL

/**
 * ImageGenerationTool — generates an image from a description using the MiniMax image API
 * and saves it to the device gallery (Pictures/Jarvis).
 *
 * Only active when the LLM provider is MiniMax.
 *
 * Trigger phrases:
 *   "create/generate/make/draw/produce an image of a sunset"
 *   "generate a picture of a dog"
 *   "make a photo of a forest at night"
 */
class ImageGenerationTool(
    private val context: Context,
    private val settings: SettingsStore
) : Tool {

    override val name = "ImageGeneration"
    override val description = "Generate an AI image from a description and save to gallery"
    override val requiresNetwork = true

    override fun schema() = ToolSchema(
        name        = name,
        description = "Generate an AI image from a text description and save it to the device gallery. Requires MiniMax as the LLM provider.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "description" to mapOf("type" to "string", "description" to "Detailed description of the image to generate")
            ),
            "required" to listOf("description")
        )
    )

    companion object {
        private const val TAG = "ImageGenerationTool"
        private const val IMAGE_MODEL = "image-01"

        private val TRIGGER_RE = Regex(
            """(?:create|generate|make|draw|produce)\s+(?:an?\s+)?(?:image|picture|photo|drawing|illustration)\s+of\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun matches(transcript: String): ToolInput? {
        // Only active for MiniMax provider
        if (!settings.llmProvider.equals("minimax", ignoreCase = true)) return null

        val match = TRIGGER_RE.find(transcript) ?: return null
        val description = match.groupValues[1].trim().ifBlank { return null }

        return ToolInput(
            transcript = transcript,
            params = mapOf("description" to description)
        )
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val description = input.param("description")
        val apiKey = settings.apiKey
        val baseUrl = settings.miniMaxBaseUrl.trimEnd('/')

        if (apiKey.isBlank()) {
            return ToolResult.Failure("No API key configured. Please add your MiniMax API key in settings.")
        }

        return try {
            Log.d(TAG, "Generating image for: \"$description\"")

            // ── 1. Call MiniMax image generation API ──────────────────────────
            val requestBody = JSONObject().apply {
                put("model", IMAGE_MODEL)
                put("prompt", description)
            }.toString()

            val responseJson = NetworkClient.post(
                url = "$baseUrl/image_generation",
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json"
                ),
                body = requestBody
            )

            // ── 2. Parse response ─────────────────────────────────────────────
            val root = JSONObject(responseJson)
            Log.d(TAG, "Image API response keys: ${root.keys().asSequence().toList()}")

            // Extract image URL or b64 from whichever structure the API returned.
            // MiniMax can return: data{image_url}, data{b64_json}, data[{url}],
            // data[{b64_json}], images[{url}], or top-level url/b64_json.
            val imageBytes: ByteArray = run {
                // 1. data as object  → data.image_url / data.b64_json
                root.optJSONObject("data")?.let { obj ->
                    when {
                        obj.has("image_url") -> return@run fetchUrl(obj.getString("image_url"))
                            ?: return ToolResult.Failure("Image URL failed safety validation.")
                        obj.has("b64_json")  -> return@run Base64.decode(obj.getString("b64_json"), Base64.DEFAULT)
                        else -> Unit
                    }
                }
                // 2. data as array   → data[0].url / data[0].b64_json
                root.optJSONArray("data")?.let { arr ->
                    if (arr.length() > 0) {
                        val item = arr.getJSONObject(0)
                        when {
                            item.has("url")      -> return@run fetchUrl(item.getString("url"))
                                ?: return ToolResult.Failure("Image URL failed safety validation.")
                            item.has("b64_json") -> return@run Base64.decode(item.getString("b64_json"), Base64.DEFAULT)
                            item.has("image_url")-> return@run fetchUrl(item.getString("image_url"))
                                ?: return ToolResult.Failure("Image URL failed safety validation.")
                            else -> Unit
                        }
                    }
                }
                // 3. images array    → images[0].url / images[0].b64_json
                root.optJSONArray("images")?.let { arr ->
                    if (arr.length() > 0) {
                        val item = arr.getJSONObject(0)
                        when {
                            item.has("url")      -> return@run fetchUrl(item.getString("url"))
                                ?: return ToolResult.Failure("Image URL failed safety validation.")
                            item.has("b64_json") -> return@run Base64.decode(item.getString("b64_json"), Base64.DEFAULT)
                            else -> Unit
                        }
                    }
                }
                // 4. top-level url / b64_json
                if (root.has("url")) {
                    return@run fetchUrl(root.getString("url"))
                        ?: return ToolResult.Failure("Image URL failed safety validation.")
                }
                if (root.has("b64_json")) {
                    return@run Base64.decode(root.getString("b64_json"), Base64.DEFAULT)
                }
                Log.w(TAG, "Unrecognised response structure: $responseJson")
                return ToolResult.Failure("Could not find image data in the API response.")
            }

            // ── 3. Save to internal storage + gallery ─────────────────────────
            val fileName = "jarvis_gen_${System.currentTimeMillis()}.jpg"
            saveToInternalStorage(imageBytes, fileName)
            val savedToGallery = saveToGallery(imageBytes, fileName)

            if (savedToGallery) {
                Log.d(TAG, "Image saved: $fileName")
                ToolResult.Success("Image generated and saved to your gallery in the Jarvis folder.")
            } else {
                ToolResult.Success("Image generated and saved internally. Check the Jarvis folder.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Image generation failed", e)
            ToolResult.Failure("Image generation failed: ${e.message?.take(100) ?: "unknown error"}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Download [url] after safety validation. Returns null if validation fails. */
    private fun fetchUrl(url: String): ByteArray? {
        if (!StoragePolicy.isSafeImageUrl(url)) return null
        Log.d(TAG, "Downloading image from: $url")
        return URL(url).openStream().use(InputStream::readBytes)
    }

    /** Save [bytes] to app-private internal storage (filesDir/pictures). */
    private fun saveToInternalStorage(bytes: ByteArray, fileName: String) {
        try {
            val dir = java.io.File(context.filesDir, "pictures").also { it.mkdirs() }
            java.io.File(dir, fileName).writeBytes(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Internal storage save failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Save [bytes] as a JPEG to Pictures/Jarvis using MediaStore (no permissions
     * needed on Android 10+).
     */
    private fun saveToGallery(bytes: ByteArray, fileName: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/Jarvis")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false

            resolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image to gallery", e)
            false
        }
    }
}
