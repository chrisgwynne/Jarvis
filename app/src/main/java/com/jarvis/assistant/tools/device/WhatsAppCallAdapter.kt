package com.jarvis.assistant.tools.device

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.framework.ToolResult
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WhatsAppCallAdapter — places a voice or video call via WhatsApp.
 *
 * WhatsApp does not expose a clean intent like `whatsapp://call?phone=…`.
 * The supported entry-point is a `ContactsContract.Data` row with one of
 * these MIME types:
 *
 *   - `vnd.android.cursor.item/vnd.com.whatsapp.voip.call`   — voice call
 *   - `vnd.android.cursor.item/vnd.com.whatsapp.video.call`  — video call
 *
 * WhatsApp adds those rows to a system contact when the contact is also a
 * WhatsApp user.  We resolve the row id by looking up the contact's
 * display name + the WhatsApp MIME, then start an `ACTION_VIEW` on the
 * `Data` URI for that row.  WhatsApp picks up the action and dials.
 *
 * Failure modes (all return [ToolResult.Failure] — caller decides whether
 * to fall back to native dial or speak the error):
 *   - Contact has no WhatsApp row (display name unknown to WhatsApp)
 *   - WhatsApp package not installed (no app to handle ACTION_VIEW)
 *   - ContactsContract query throws (permission denied, transient I/O)
 */
class WhatsAppCallAdapter(private val context: Context) {

    enum class Mode { VOICE, VIDEO }

    companion object {
        private const val TAG = "WaCallAdapter"
        private const val MIME_VOICE = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        private const val MIME_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
        private const val WHATSAPP_PKG = "com.whatsapp"
    }

    suspend fun call(contact: ContactLookup.Contact, mode: Mode): ToolResult {
        val mime = if (mode == Mode.VIDEO) MIME_VIDEO else MIME_VOICE
        val verb = if (mode == Mode.VIDEO) "video-call" else "call"

        // ContactsContract queries are usually < 50 ms but cap them so a
        // misbehaving provider can't hang the assistant.
        val rowId = withTimeoutOrNull(1_500L) { findWhatsAppRow(contact.displayName, mime) }
        if (rowId == null) {
            Log.w(TAG, "[WA_CALL_NO_ROW] name='${contact.displayName}' mime=$mime")
            return ToolResult.Failure(
                "${contact.displayName} isn't on WhatsApp, or I can't see them there."
            )
        }

        val dataUri: Uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, rowId)
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, dataUri)
                    .setPackage(WHATSAPP_PKG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.d(TAG, "[WA_CALL_LAUNCHED] name='${contact.displayName}' mode=$mode rowId=$rowId")
            val niceVerb = if (mode == Mode.VIDEO) "Video-calling" else "WhatsApp-calling"
            ToolResult.Success("$niceVerb ${contact.displayName}.")
        } catch (e: Exception) {
            Log.w(TAG, "[WA_CALL_FAILED] ${e.message}")
            ToolResult.Failure("I couldn't open WhatsApp to $verb ${contact.displayName}.")
        }
    }

    /**
     * Look up the `ContactsContract.Data._ID` for the row whose contact
     * display name matches [name] and whose MIME is [mime].  Returns null
     * when no such row exists (contact has no WhatsApp profile).
     */
    private fun findWhatsAppRow(name: String, mime: String): Long? = try {
        val cursor = context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME),
            "${ContactsContract.Data.MIMETYPE} = ? AND " +
                "${ContactsContract.Data.DISPLAY_NAME} LIKE ?",
            arrayOf(mime, "%$name%"),
            null
        )
        cursor?.use { c ->
            // Exact (case-insensitive) match takes priority — otherwise the
            // first LIKE hit, which mirrors how ContactLookup itself resolves.
            val nameIdx = c.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
            val idIdx   = c.getColumnIndex(ContactsContract.Data._ID)
            var fallback: Long? = null
            while (c.moveToNext()) {
                val rowId = c.getLong(idIdx)
                val rowName = c.getString(nameIdx) ?: continue
                if (rowName.equals(name, ignoreCase = true)) return rowId
                if (fallback == null) fallback = rowId
            }
            fallback
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "[WA_CALL_LOOKUP_DENIED] ${e.message}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "[WA_CALL_LOOKUP_FAILED] ${e.message}")
        null
    }
}
