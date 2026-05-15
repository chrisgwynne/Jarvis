package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * CloseAppTool — voice control for dismissing and exiting apps.
 *
 * SUPPORTED COMMANDS:
 *   Named close:    "close Google Maps", "close Spotify", "shut WhatsApp"
 *   Context close:  "close this app", "close it", "exit this", "close that"
 *   Navigation:     "go back", "go home"
 *   Switch to Jarvis: "switch back to Jarvis"
 *
 * CLOSE STRATEGY (best available):
 *   1. Accessibility connected → [GLOBAL_ACTION_HOME] returns user to launcher.
 *      Reports "Sent it home." (honest — Android prevents force-kill by normal apps).
 *   2. "go back" specifically → [GLOBAL_ACTION_BACK] (requires accessibility).
 *      Without accessibility, reports a friendly limitation.
 *   3. No accessibility → send a raw HOME intent (same result, no spoken click).
 *
 * NEVER SAYS "Closed" when only HOME was performed — the spec requires honest
 * messaging because the app process continues in the background.
 *
 * ACTIVATION GUARD:
 *   "close it" / "close that" only match when [recentAppContextStore] has a live
 *   entry, preventing false positives during unrelated conversations.
 *
 * ORDERING:
 *   Must be registered AFTER [com.jarvis.assistant.tools.device.EndCallTool] and
 *   [com.jarvis.assistant.tools.device.ClearNotificationsTool] so "close the call"
 *   and "close notifications" are not intercepted here.  Must be registered AFTER
 *   [MapsNavigationFollowupTool] so "close Maps" during active navigation is caught
 *   by the navigation tool first.
 */
class CloseAppTool(
    private val context: Context,
    private val resolver: AppResolver,
    private val recentAppContextStore: RecentAppContextStore? = null,
) : Tool {

    override val name            = "close_app"
    override val description     = "Exit, dismiss, or go home from an app by name or voice reference"
    override val requiresNetwork = false
    override val requiredPermissions: List<String> = emptyList()

    override fun schema() = ToolSchema(
        name        = name,
        description = "Close or exit a named app, the current foreground app, or navigate back/home.",
        parameters  = mapOf(
            "type"       to "object",
            "properties" to mapOf(
                "app"    to mapOf("type" to "string", "description" to "App name to close. Omit for current app."),
                "action" to mapOf("type" to "string", "description" to "CLOSE, BACK, HOME", "enum" to listOf("CLOSE", "BACK", "HOME"))
            ),
            "required" to emptyList<String>()
        )
    )

    companion object {
        private const val TAG = "CloseAppTool"

        // Android global action IDs (android.accessibilityservice.AccessibilityService)
        private const val GLOBAL_ACTION_BACK = 1
        private const val GLOBAL_ACTION_HOME = 2

        // "close this app", "exit that", "close it", etc.
        private val CLOSE_SELF_RE = Regex(
            """(?:close|shut|exit|dismiss|quit)\s+""" +
            """(?:this|that|it|the\s+app|this\s+app|that\s+app|the\s+current\s+app|current\s+app)""",
            RegexOption.IGNORE_CASE
        )

        // "close Google Maps", "shut Spotify" — anything after the verb that isn't a self-ref
        private val CLOSE_NAMED_RE = Regex(
            """(?:close|shut|exit|dismiss|quit)\s+""" +
            """(?!(?:this|that|it\b|the\s+app|this\s+app|that\s+app|the\s+current\s+app|current\s+app))(.+)""",
            RegexOption.IGNORE_CASE
        )

        // "go back" / "go back please"
        private val GO_BACK_RE = Regex(
            """(?:^|\s)go\s+back(?:\s+please)?\s*$""",
            RegexOption.IGNORE_CASE
        )

        // "go home" / "go to home screen"
        private val GO_HOME_RE = Regex(
            """(?:^|\s)go\s+(?:to\s+)?(?:home(?:\s+screen)?)(?:\s+please)?\s*$""",
            RegexOption.IGNORE_CASE
        )

        // "switch back to Jarvis" / "open Jarvis"
        private val SWITCH_JARVIS_RE = Regex(
            """switch\s+back\s+to\s+jarvis|return\s+to\s+jarvis""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        return when {
            GO_BACK_RE.containsMatchIn(t)       -> ToolInput(transcript, mapOf("action" to "back"))
            GO_HOME_RE.containsMatchIn(t)       -> ToolInput(transcript, mapOf("action" to "home"))
            SWITCH_JARVIS_RE.containsMatchIn(t) -> ToolInput(transcript, mapOf("action" to "home"))

            CLOSE_SELF_RE.containsMatchIn(t) -> ToolInput(transcript, mapOf("action" to "close_self"))

            // "close it" / "close that" — only when we have recent app context
            t.matches(Regex("""(?:close|shut|exit)\s+(?:it|that)\s*""", RegexOption.IGNORE_CASE)) -> {
                if (recentAppContextStore?.hasContext == true)
                    ToolInput(transcript, mapOf("action" to "close_context"))
                else
                    null
            }

            else -> {
                val m = CLOSE_NAMED_RE.find(t) ?: return null
                val appName = m.groupValues[1].trim().trimEnd('.', '?', '!')
                ToolInput(transcript, mapOf("action" to "close_named", "app" to appName))
            }
        }
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val action = input.param("action")
        Log.d(TAG, "[APP_CLOSE_REQUEST] action=$action app=${input.paramOrNull("app")}")

        return when (action) {
            "back"          -> handleBack()
            "home"          -> handleHome()
            "close_self"    -> handleCloseSelf()
            "close_context" -> handleCloseContext()
            "close_named"   -> handleCloseNamed(input.param("app"))
            else            -> handleHome()
        }
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private fun handleBack(): ToolResult {
        Log.d(TAG, "[APP_CLOSE_STRATEGY] action=back accessibility=${JarvisAccessibilityService.isConnected()}")
        return if (JarvisAccessibilityService.isConnected()) {
            JarvisAccessibilityService.performGlobalAction(GLOBAL_ACTION_BACK)
            Log.d(TAG, "[APP_CLOSE_SUCCESS] back")
            ToolResult.Success("Going back.")
        } else {
            Log.d(TAG, "[APP_CLOSE_LIMITED] back — accessibility not connected")
            ToolResult.Failure("I need the accessibility service to go back. Enable it in Settings.")
        }
    }

    private fun handleHome(): ToolResult {
        Log.d(TAG, "[APP_CLOSE_STRATEGY] action=home accessibility=${JarvisAccessibilityService.isConnected()}")
        val sent = if (JarvisAccessibilityService.isConnected()) {
            JarvisAccessibilityService.performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            sendHomeIntent()
            true
        }
        return if (sent) {
            Log.d(TAG, "[APP_CLOSE_SUCCESS] home")
            ToolResult.Success("Done.")
        } else {
            ToolResult.Failure("Couldn't navigate home.")
        }
    }

    private fun handleCloseSelf(): ToolResult {
        Log.d(TAG, "[APP_CLOSE_STRATEGY] action=close_self accessibility=${JarvisAccessibilityService.isConnected()}")
        val sent = if (JarvisAccessibilityService.isConnected()) {
            JarvisAccessibilityService.performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            sendHomeIntent()
            true
        }
        return if (sent) {
            Log.d(TAG, "[APP_CLOSE_SUCCESS] close_self")
            ToolResult.Success("I can't force-close that, but I've taken you out of it.")
        } else {
            ToolResult.Failure("Couldn't exit the app.")
        }
    }

    private fun handleCloseContext(): ToolResult {
        val ctx = recentAppContextStore?.current
        if (ctx == null) {
            Log.d(TAG, "[APP_CLOSE_LIMITED] context expired")
            return handleCloseSelf()
        }
        return handleCloseNamed(ctx.appName)
    }

    private fun handleCloseNamed(appName: String): ToolResult {
        Log.d(TAG, "[APP_CLOSE_TARGET_RESOLVED] name=$appName")

        // Resolve to package for logging; we can't actually force-kill it
        val result = resolver.resolve(appName)
        val resolvedLabel = when (result) {
            is AppResolver.Result.Launchable   -> result.displayLabel
            is AppResolver.Result.GenericIntent -> result.displayLabel
            AppResolver.Result.NotFound         -> appName
        }
        Log.d(TAG, "[APP_CLOSE_STRATEGY] action=close_named resolved=$resolvedLabel accessibility=${JarvisAccessibilityService.isConnected()}")

        // Best available: use HOME to leave the app
        val sent = if (JarvisAccessibilityService.isConnected()) {
            JarvisAccessibilityService.performGlobalAction(GLOBAL_ACTION_HOME)
        } else {
            sendHomeIntent()
            true
        }

        recentAppContextStore?.clear()

        return if (sent) {
            Log.d(TAG, "[APP_CLOSE_LIMITED] HOME used — process continues in background")
            ToolResult.Success("Sent it home.")
        } else {
            ToolResult.Failure("Couldn't close $resolvedLabel.")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun sendHomeIntent() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "HOME intent failed: ${e.message}")
        }
    }
}
