package com.jarvis.assistant.accessibility

/**
 * ScreenSnapshot — point-in-time view of what is currently on screen, captured
 * via the [JarvisAccessibilityService].
 *
 * Two complementary surfaces for the same moment:
 *
 *   * [textTree] — a flattened, ordered list of every visible text-bearing or
 *     interactive node, with bounding boxes.  Cheap to build, cheap to grep,
 *     small enough to drop straight into a prompt.  Use for "what does the
 *     screen *say*?" or for matching by label when actuating.
 *
 *   * [screenshotPngBase64] — a base64 PNG of the foreground display, captured
 *     via Accessibility's takeScreenshot API (no MediaProjection consent
 *     dialog).  Optional — null when the API is unavailable (API < 30) or
 *     the take call failed.
 *
 * The snapshot is immutable; capturing a fresh one is a single
 * [ScreenInspector.snapshot] call.
 *
 * @property foregroundPackage  Package name of the currently focused app, or
 *                              null when no foreground window is identifiable.
 * @property capturedAtMs       Wall-clock ms at capture; used for staleness
 *                              checks before [ScreenActuator] dispatches an
 *                              action against the same node tree.
 */
data class ScreenSnapshot(
    val foregroundPackage   : String?,
    val textTree            : List<ScreenNode>,
    val screenshotPngBase64 : String?,
    val capturedAtMs        : Long = System.currentTimeMillis()
) {
    /** True if the snapshot is older than [maxAgeMs] and must be re-captured before acting. */
    fun isStale(maxAgeMs: Long = 4_000L): Boolean =
        System.currentTimeMillis() - capturedAtMs > maxAgeMs

    /**
     * One-line text rendering of the visible screen for prompt injection.
     * Each visible node is rendered as `[N text]` (where N is the node index)
     * so the LLM can refer back to a specific node by index when the caller
     * later resolves a tap target.
     */
    fun toPromptText(maxChars: Int = 2_000): String {
        val sb = StringBuilder()
        for ((idx, node) in textTree.withIndex()) {
            val label = node.text.ifBlank { node.contentDescription }.trim()
            if (label.isBlank()) continue
            val tag = if (node.isClickable) "btn" else "text"
            val piece = "[$idx $tag] $label"
            if (sb.length + piece.length + 1 > maxChars) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(piece)
        }
        return sb.toString()
    }
}

/**
 * ScreenNode — flattened view of one [android.view.accessibility.AccessibilityNodeInfo].
 *
 * Carries only the fields the rest of the system needs so the snapshot is
 * cheap to keep around and free of references back to the live node tree
 * (which can be invalidated by the OS as soon as the user scrolls).
 */
data class ScreenNode(
    /** Stable index inside [ScreenSnapshot.textTree] — used as a tap target id. */
    val index: Int,
    /** Visible text on the node (TextView.text equivalent), trimmed. */
    val text: String,
    /** Content description (icon-only buttons rely on this). */
    val contentDescription: String,
    /** Resource view id, if any (e.g. "com.app:id/send"). */
    val viewId: String,
    /** Class name (helpful for filters: android.widget.Button vs ImageView). */
    val className: String,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean,
    /** Pixel rectangle of the node in screen coordinates. */
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int
) {
    val centerX: Int get() = (boundsLeft + boundsRight) / 2
    val centerY: Int get() = (boundsTop  + boundsBottom) / 2

    /** Best human-facing label for matching ("send", "yes", "Catherine"). */
    val matchLabel: String
        get() = listOf(text, contentDescription).firstOrNull { it.isNotBlank() }?.trim() ?: ""
}
