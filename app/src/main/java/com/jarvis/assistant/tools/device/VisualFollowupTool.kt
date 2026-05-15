package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import com.jarvis.assistant.vision.VisualContextStore
import java.io.File

/**
 * VisualFollowupTool — handles contextual follow-up actions on the most recent
 * visual context stored in [VisualContextStore].
 *
 * SUPPORTED FOLLOW-UPS:
 *   "read that again"          → re-speak OCR text from context
 *   "show that" / "open it"    → open image in viewer
 *   "send that to [name]"      → share image via system share sheet
 *   "save that"                → confirm already saved (camera captures auto-save)
 *   "remind me about this"     → fall through to LLM for reminder creation
 *
 * ACTIVATION GUARD:
 *   [matches] returns null when [VisualContextStore.hasContext] is false so these
 *   follow-up phrases are only intercepted when visual context is live.  Without
 *   the guard, "read that again" for a non-vision transcript would misfire.
 *
 * LOCAL-FIRST:
 *   All actions here are local — file access, system share intent, in-memory
 *   context read.  No LLM, no network.
 */
class VisualFollowupTool(
    private val context: Context,
    private val visualContextStore: VisualContextStore,
) : Tool {

    override val name            = "visual_followup"
    override val description     = "Handles follow-up actions on the most recent visual context"
    override val requiresNetwork = false
    override val requiredPermissions: List<String> = emptyList()

    override fun schema() = ToolSchema(
        name        = name,
        description = "Act on the last captured image or screenshot: read text again, " +
                      "share the image, open it, or save it.",
        parameters  = mapOf(
            "type"       to "object",
            "properties" to emptyMap<String, Any>(),
            "required"   to emptyList<String>(),
        )
    )

    companion object {
        private const val TAG = "VisualFollowupTool"
        private const val FILE_PROVIDER_AUTHORITY = "com.jarvis.assistant.provider"

        private val READ_AGAIN = Regex(
            """read\s+(?:that|it)(?:\s+again)?|say\s+(?:that|it)\s+again|repeat\s+(?:that|it)""",
            RegexOption.IGNORE_CASE
        )
        private val SHOW_IT = Regex(
            """show\s+(?:me\s+)?(?:that|it)|open\s+(?:that|it|the\s+(?:image|photo|screenshot))""",
            RegexOption.IGNORE_CASE
        )
        private val SEND_IT = Regex(
            """send\s+(?:that|it|this)\s+to|share\s+(?:that|it|this)(?:\s+with)?""",
            RegexOption.IGNORE_CASE
        )
        private val SAVE_IT = Regex(
            """save\s+(?:that|it|this)""",
            RegexOption.IGNORE_CASE
        )

        private val ALL_TRIGGERS = Regex(
            """${READ_AGAIN.pattern}|${SHOW_IT.pattern}|${SEND_IT.pattern}|${SAVE_IT.pattern}""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Only match when there is an active visual context — prevents "read that
     * again" from firing for non-vision utterances.
     */
    override fun matches(transcript: String): ToolInput? {
        if (!visualContextStore.hasContext) return null
        return if (ALL_TRIGGERS.containsMatchIn(transcript.trim()))
            ToolInput(transcript.trim()) else null
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val ctx = visualContextStore.current
            ?: return ToolResult.Failure("I don't have a recent image in memory.")

        val transcript = input.transcript

        return when {
            READ_AGAIN.containsMatchIn(transcript) -> handleReadAgain(ctx)
            SHOW_IT.containsMatchIn(transcript)    -> handleShowIt(ctx)
            SEND_IT.containsMatchIn(transcript)    -> handleSendIt(ctx, transcript)
            SAVE_IT.containsMatchIn(transcript)    -> handleSaveIt(ctx)
            else -> ToolResult.Failure("Not sure what you want to do with that.")
        }
    }

    // ── Action handlers ───────────────────────────────────────────────────────

    private fun handleReadAgain(ctx: VisualContextStore.VisualContext): ToolResult {
        val text = ctx.ocrText
        val summary = ctx.summary
        return when {
            !text.isNullOrBlank() -> {
                val spoken = if (text.length > 400) text.take(400) + "…" else text
                ToolResult.Success("It says: $spoken")
            }
            !summary.isNullOrBlank() -> ToolResult.Success(summary)
            else -> ToolResult.Success("I don't have any text from that image.")
        }
    }

    private fun handleShowIt(ctx: VisualContextStore.VisualContext): ToolResult {
        val path = ctx.imageFilePath
            ?: return ToolResult.Success("I can't find the image file.")

        val file = File(path)
        if (!file.exists()) return ToolResult.Success("That image doesn't seem to be on disk anymore.")

        try {
            val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "[VISUAL_SHOW] opened image viewer")
            return ToolResult.Success("Opening the image.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not open image: ${e.message}")
            return ToolResult.Success("I saved the image but couldn't open a viewer.")
        }
    }

    private fun handleSendIt(ctx: VisualContextStore.VisualContext, transcript: String): ToolResult {
        val path = ctx.imageFilePath
            ?: return ToolResult.Failure("I don't have a saved image to send.")

        val file = File(path)
        if (!file.exists()) return ToolResult.Failure("That image is no longer available.")

        try {
            val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share image").also {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Log.d(TAG, "[VISUAL_SEND] share sheet opened")
            return ToolResult.Success("Opening the share sheet.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not open share sheet: ${e.message}")
            return ToolResult.Failure("Couldn't open the share sheet.")
        }
    }

    private fun handleSaveIt(ctx: VisualContextStore.VisualContext): ToolResult {
        // Camera captures are already saved to the gallery by CameraCaptureManager.
        // Screenshots are already in the gallery. Confirm to the user.
        return when (ctx.source) {
            VisualContextStore.Source.PHONE_CAMERA,
            VisualContextStore.Source.FRONT_CAMERA -> ToolResult.Success("Already saved to the gallery.")
            VisualContextStore.Source.SCREENSHOT   -> ToolResult.Success("It's already in your screenshots folder.")
            VisualContextStore.Source.GALLERY      -> ToolResult.Success("That's already a saved image.")
            VisualContextStore.Source.META_GLASSES -> ToolResult.Success("Already saved.")
        }
    }
}
