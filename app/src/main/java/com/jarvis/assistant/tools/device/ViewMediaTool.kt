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
 * ViewMediaTool — opens the most recently captured photo / video /
 * recording in the user's default viewer (Gallery / Photos / etc.).
 *
 * Two ways the tool gets the URI:
 *   1. ContextualFollowupResolver emits a Dispatch with
 *      params["uri"] / params["kind"] from RecentActionContextStore.
 *   2. No params → falls back to [MediaContextStore.peek] for the
 *      latest-published file (set by CameraCaptureTool on success).
 *
 * Hands the file URI to the system via `Intent.ACTION_VIEW` wrapped
 * in FileProvider so other apps can read it — `file://` URIs have
 * been banned since API 24 and would crash the receiving app.
 *
 * Discoverable directly too: "show me the last photo", "show
 * the selfie I just took", "open the screenshot".
 */
class ViewMediaTool(private val context: Context) : Tool {

    override val name = "view_media"
    override val description = "Open the most recently captured photo, video, or recording in the gallery."
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions = emptyList<String>()

    companion object {
        private const val TAG = "ViewMediaTool"

        /** Direct-discovery patterns — user explicitly asks to view. */
        private val PATTERNS = Regex(
            """(?ix)
            ^\s*(?:please\s+)?
            (?:show|open|view|see|let\s+me\s+see)
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
        description = "Open the most recently captured media (photo / video / recording) in the user's default viewer.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "uri" to mapOf("type" to "string",
                    "description" to "Optional file path or content:// URI; defaults to the latest capture."),
                "kind" to mapOf("type" to "string",
                    "description" to "Optional spoken descriptor (photo / selfie / video)."),
            ),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val explicitUri = input.param("uri").trim()
        val (path, mime, kind) = resolveTarget(explicitUri)
            ?: return ToolResult.Failure("I haven't captured anything to open yet.")

        return try {
            val uri = buildShareUri(path)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(viewIntent)
            Log.d(TAG, "[MEDIA_URI_OPENED] uri=$uri mime=$mime kind=$kind")
            ToolResult.Success("Opening the $kind.")
        } catch (e: Exception) {
            Log.w(TAG, "[VIEW_MEDIA_FAILED] ${e.message}")
            ToolResult.Failure("I couldn't open the $kind.")
        }
    }

    /**
     * Locate the file to open.  If [explicitUri] is set, parse it as
     * either a content URI (used as-is) or a filesystem path.
     * Otherwise consult [MediaContextStore].  Returns null when no
     * media is available.
     */
    private fun resolveTarget(explicitUri: String): Triple<String, String, String>? {
        if (explicitUri.isNotBlank()) {
            // content:// URIs go through as-is later; file paths fall
            // through to FileProvider.  MIME defaults to image/jpeg
            // since we can't introspect arbitrary URIs.
            return Triple(explicitUri, "image/jpeg", "photo")
        }
        val e = MediaContextStore.peek() ?: return null
        return Triple(e.filePath, e.mimeType, e.kind)
    }

    /** Wrap a filesystem path in a FileProvider URI; pass content URIs through. */
    private fun buildShareUri(pathOrUri: String): Uri {
        if (pathOrUri.startsWith("content://")) return Uri.parse(pathOrUri)
        val file = File(pathOrUri)
        val authority = context.packageName + ".fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }
}
