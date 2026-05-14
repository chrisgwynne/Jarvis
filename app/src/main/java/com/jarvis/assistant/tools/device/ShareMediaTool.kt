package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.jarvis.assistant.tools.device.media.MediaContextStore
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import java.io.File

/**
 * ShareMediaTool — hand the most recently captured media to the
 * system share sheet (`Intent.ACTION_SEND`).  User picks the target
 * app (Messages, WhatsApp, Gmail, …) — we just produce the share
 * intent with a FileProvider URI and the right MIME type.
 *
 * Discoverable via "share that" / "share the photo" / "send the
 * selfie" via the ContextualFollowupResolver, AND directly via its
 * own [PATTERNS] for explicit utterances.
 */
class ShareMediaTool(private val context: Context) : Tool {

    override val name = "share_media"
    override val description = "Share the most recently captured photo, video, or recording via the system share sheet."
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions = emptyList<String>()

    companion object {
        private const val TAG = "ShareMediaTool"

        private val PATTERNS = Regex(
            """(?ix)
            ^\s*(?:please\s+)?
            (?:share|send)
            \s+(?:me\s+)?
            (?:the\s+|that\s+|this\s+|my\s+|last\s+|latest\s+|recent\s+)?
            (?:selfie|photo|picture|image|video|recording|screenshot)
            (?:\s+(?:i\s+)?just\s+(?:took|made|captured|shot))?
            \s*[.!?]?\s*$
            """,
        )
    }

    override fun matches(transcript: String): ToolInput? =
        if (PATTERNS.matches(transcript.trim()))
            ToolInput(transcript, emptyMap())
        else null

    override fun schema() = ToolSchema(
        name        = name,
        description = "Open the system share sheet with the most recently captured media.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "uri" to mapOf("type" to "string",
                    "description" to "Optional file path or content:// URI; defaults to the latest capture."),
            ),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val explicitUri = input.param("uri").trim()
        val (path, mime, kind) = resolveTarget(explicitUri)
            ?: return ToolResult.Failure("There's nothing recent to share.")

        return try {
            val uri = buildShareUri(path)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(sendIntent, "Share $kind")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            Log.d(TAG, "[MEDIA_URI_OPENED] action=share uri=$uri mime=$mime")
            ToolResult.Success("Pick where to send the $kind.")
        } catch (e: Exception) {
            Log.w(TAG, "[SHARE_MEDIA_FAILED] ${e.message}")
            ToolResult.Failure("I couldn't open the share sheet.")
        }
    }

    private fun resolveTarget(explicitUri: String): Triple<String, String, String>? {
        if (explicitUri.isNotBlank()) {
            return Triple(explicitUri, "image/jpeg", "photo")
        }
        val e = MediaContextStore.peek() ?: return null
        return Triple(e.filePath, e.mimeType, e.kind)
    }

    private fun buildShareUri(pathOrUri: String): Uri {
        if (pathOrUri.startsWith("content://")) return Uri.parse(pathOrUri)
        val file = File(pathOrUri)
        val authority = context.packageName + ".fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }
}
