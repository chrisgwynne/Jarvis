package com.jarvis.assistant.tools.device

import android.util.Log
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.ScreenNode
import com.jarvis.assistant.runtime.FailurePhrases
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * TapScreenTool — "tap the send button", "press yes", "click reply".
 *
 * Captures a snapshot, finds the best matching clickable node by label /
 * content description, and dispatches a click via [JarvisAccessibilityService].
 *
 * SAFETY:
 *   * Only fires on explicit "tap"/"press"/"click" verbs — never auto-tap.
 *   * The snapshot's staleness window guards against tapping on a screen
 *     the user has already scrolled past in the interim.
 *   * Refuses to tap inputs / payment-flavoured controls without a clearer
 *     match — see [SENSITIVE_LABELS].
 */
class TapScreenTool : Tool {

    override val name        = "tap_screen"
    override val description = "Tap a button or link by name on the screen Jarvis can see"

    private val TRIGGERS = Regex(
        """(?:tap|press|click|hit|select|choose)\s+(?:the\s+|on\s+(?:the\s+)?)?(.+)""",
        RegexOption.IGNORE_CASE
    )

    /** Labels we refuse to auto-tap without an exact, unambiguous match. */
    private val SENSITIVE_LABELS = setOf(
        "pay", "buy", "send money", "transfer", "confirm payment", "pay now",
        "delete account", "uninstall", "factory reset", "wipe", "logout"
    )

    override fun matches(transcript: String): ToolInput? {
        val m = TRIGGERS.find(transcript.trim()) ?: return null
        val target = m.groupValues[1].trim().trimEnd('.', '!', '?').trim()
        if (target.isBlank()) return null
        return ToolInput(transcript, mapOf("target" to target))
    }

    override fun schema(): ToolSchema = ToolSchema(
        name = name,
        description = "Tap a visible button, link or row on the foreground screen. The 'target' should be the visible label or short description of what to click.",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "target" to mapOf(
                    "type" to "string",
                    "description" to "Visible label / description of the button, e.g. 'send', 'reply', 'yes'."
                )
            ),
            "required" to listOf("target")
        )
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val target = input.param("target").lowercase().trim()
        if (target.isBlank()) return ToolResult.Failure("What should I tap?")

        if (!JarvisAccessibilityService.isConnected()) {
            return ToolResult.Failure(
                "I need the Accessibility permission for that — turn it on " +
                "in Settings → Accessibility → Jarvis."
            )
        }

        // Skip the screenshot — this path is text-tree only and latency-sensitive.
        val snapshot = JarvisAccessibilityService.snapshot(withScreenshot = false)
            ?: return ToolResult.Failure("I couldn't see the screen.")

        if (snapshot.isStale()) {
            return ToolResult.Failure("The screen changed before I could tap.")
        }

        val candidates = snapshot.textTree.filter { it.isClickable && it.isEnabled }
        if (candidates.isEmpty()) return ToolResult.Failure("Nothing tappable on screen.")

        val match = pickMatch(candidates, target)
            ?: return ToolResult.Failure("I can't find $target on screen.")

        if (match.matchLabel.lowercase() in SENSITIVE_LABELS && match.matchLabel.lowercase() != target) {
            return ToolResult.Failure("I won't tap $match.matchLabel — say it exactly.")
        }

        val ok = JarvisAccessibilityService.click(match)
        return if (ok) {
            ToolResult.Success(spokenFeedback = "Done.", silent = true)
        } else {
            Log.w("TapScreenTool", "click dispatch failed for '${match.matchLabel}'")
            ToolResult.Failure(FailurePhrases.GENERIC)
        }
    }

    /**
     * Score-based label match.  Exact and prefix beat substring; ties broken
     * by node area (smaller = more specific button vs container).
     */
    private fun pickMatch(candidates: List<ScreenNode>, needle: String): ScreenNode? {
        val scored = candidates.mapNotNull { node ->
            val label = node.matchLabel.lowercase()
            if (label.isBlank()) return@mapNotNull null
            val score = when {
                label == needle               -> 1000
                label.startsWith("$needle ")  -> 800
                label == needle.removePrefix("the ") -> 750
                label.startsWith(needle)      -> 700
                label.contains(" $needle ")   -> 500
                label.contains(needle)        -> 300
                else                          -> 0
            }
            if (score == 0) null else node to score
        }
        if (scored.isEmpty()) return null
        // Best score wins; on ties prefer smaller bounds (the actual button,
        // not its surrounding row container).
        return scored
            .sortedWith(
                compareByDescending<Pair<ScreenNode, Int>> { it.second }
                    .thenBy { val n = it.first; (n.boundsRight - n.boundsLeft) * (n.boundsBottom - n.boundsTop) }
            )
            .first().first
    }
}
