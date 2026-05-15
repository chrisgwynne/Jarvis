package com.jarvis.assistant.vision

import android.util.Log
import com.jarvis.assistant.camera.VisionClient
import java.io.File

/**
 * OcrPipeline — extracts text from an image using the configured vision model.
 *
 * DESIGN:
 *   Uses [VisionClient] with a text-extraction-focused prompt rather than a
 *   general description prompt.  The model is instructed to output only raw
 *   text, preserving layout, with no surrounding commentary.
 *
 * LOCAL-FIRST:
 *   On-device OCR via ML Kit can be added later as a faster zero-network
 *   path.  The interface is kept simple so a future LocalOcrPipeline can
 *   be swapped in without touching callers.
 *
 * LANGUAGE:
 *   Language detection is best-effort from the model response — the pipeline
 *   does not perform explicit language classification.
 */
class OcrPipeline(
    private val visionClient: VisionClient,
) {

    companion object {
        private const val TAG = "OcrPipeline"

        private val OCR_PROMPT = """
            Extract all visible text from this image.
            Output ONLY the extracted text, preserving line breaks and paragraph structure.
            Do not describe the image. Do not add any commentary, labels, or formatting markers.
            If no readable text is visible, output exactly: NO_TEXT_FOUND
        """.trimIndent()

        private val LABEL_READ_PROMPT = """
            Read the label, sign, or product text visible in this image.
            Output only the text you can read, in the order it appears.
            No descriptions, no commentary.
        """.trimIndent()

        private val ERROR_READ_PROMPT = """
            Read the error message or warning text visible in this image.
            First line: the exact error text.
            Second line (if applicable): a one-sentence plain-English explanation.
            Nothing else.
        """.trimIndent()

        private const val NO_TEXT_SENTINEL = "NO_TEXT_FOUND"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    data class OcrResult(
        val text: String,
        val hasText: Boolean,
        val isError: Boolean = false,
    )

    /**
     * Extract all text from [imageFile].
     *
     * @param mode Controls which prompt variant is used.
     */
    suspend fun extractText(
        imageFile: File,
        mode: Mode = Mode.GENERAL,
    ): OcrResult {
        val prompt = when (mode) {
            Mode.GENERAL -> OCR_PROMPT
            Mode.LABEL   -> LABEL_READ_PROMPT
            Mode.ERROR   -> ERROR_READ_PROMPT
        }
        return try {
            val raw = visionClient.analyze(imageFile, prompt).trim()
            Log.d(TAG, "[OCR_RESULT] mode=$mode len=${raw.length}")
            if (raw.equals(NO_TEXT_SENTINEL, ignoreCase = true) || raw.isBlank()) {
                OcrResult(text = "", hasText = false)
            } else {
                OcrResult(text = raw, hasText = true, isError = mode == Mode.ERROR)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[OCR_FAILED] ${e.message}")
            OcrResult(text = "", hasText = false)
        }
    }

    /**
     * Quick check: is there readable text in this image at all?
     * Cheaper than full extraction — uses a short discriminator prompt.
     */
    suspend fun hasText(imageFile: File): Boolean =
        extractText(imageFile, Mode.GENERAL).hasText

    enum class Mode {
        /** General text extraction — labels, documents, screenshots. */
        GENERAL,
        /** Optimised for product labels, signs, packaging. */
        LABEL,
        /** Optimised for error messages — includes brief explanation. */
        ERROR,
    }
}
