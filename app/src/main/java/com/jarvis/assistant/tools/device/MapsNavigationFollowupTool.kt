package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.maps.MapsIntentHandler
import com.jarvis.assistant.maps.MapsNavigationContext
import com.jarvis.assistant.maps.MapsNavigationContextStore
import com.jarvis.assistant.maps.TravelMode
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * MapsNavigationFollowupTool — starts, switches, or stops navigation after
 * a route has been opened by [NavigateTool] or [DirectionsTool].
 *
 * SUPPORTED COMMANDS:
 *
 *   Start navigation:
 *     "start driving directions"    → google.navigation:q=DEST&mode=d
 *     "start walking directions"    → google.navigation:q=DEST&mode=w
 *     "driving start directions"    → same (spoken word-order variant)
 *     "begin navigation"
 *     "start it"  (requires active nav context)
 *     "go"        (requires active nav context)
 *     "take me there" (requires active nav context)
 *     "walk there" / "drive there"
 *
 *   Mode switch:
 *     "switch to walking"  → re-launch with mode=w
 *     "switch to driving"  → re-launch with mode=d
 *     "change to walking"
 *     "make it walking"
 *
 *   Stop navigation:
 *     "stop navigation"    → HOME intent + clear nav context
 *     "end route"
 *     "cancel directions"
 *     "close Maps"         (only when nav context is active)
 *
 * ACTIVATION GUARD:
 *   Weak triggers ("go", "start it", "take me there", "close Maps") only match
 *   when [navContextStore] has a live entry.  Strong triggers ("start driving
 *   directions", "begin navigation") match regardless.
 *
 * ORDERING:
 *   Registered BEFORE [CloseAppTool] in [ToolRegistry] so "close Maps" during
 *   active navigation lands here, giving the user a route-aware stop instead of
 *   a generic app-dismiss.
 *
 * METHOD:
 *   Uses [MapsIntentHandler.navigationUri] + [MapsIntentHandler.open] which
 *   launches google.navigation:q=DEST&mode=X — the fastest zero-UI path to
 *   active turn-by-turn navigation.
 */
class MapsNavigationFollowupTool(
    private val context: Context,
    private val navContextStore: MapsNavigationContextStore,
    private val mapsIntents: MapsIntentHandler,
) : Tool {

    override val name            = "maps_nav_followup"
    override val description     = "Start, switch mode, or stop an active Maps navigation"
    override val requiresNetwork = false
    override val requiredPermissions: List<String> = emptyList()

    override fun schema() = ToolSchema(
        name        = name,
        description = "Start, switch mode, or cancel an active Google Maps navigation route.",
        parameters  = mapOf(
            "type"       to "object",
            "properties" to mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("start", "stop", "switch_mode")),
                "mode"   to mapOf("type" to "string", "enum" to listOf("DRIVING", "WALKING", "BICYCLING", "TRANSIT"))
            ),
            "required" to emptyList<String>()
        )
    )

    companion object {
        private const val TAG = "MapsNavFollowup"

        // Strong triggers — always match (clear navigation intent)
        private val STRONG_START = Regex(
            // "start driving directions" / "start walking" / "driving start directions"
            """(?:start|begin|launch)\s+(?:driving|walking|cycling|transit|navigation|route|directions?)""" +
            """|(?:driving|walking|cycling|transit)\s+(?:start|begin)\s+directions?""" +
            // "drive there" / "walk there"
            """|(?:drive|walk|cycle)\s+there""" +
            // "begin navigation"
            """|begin\s+navigation""",
            RegexOption.IGNORE_CASE
        )

        // Mode switch — always match
        private val MODE_SWITCH = Regex(
            """(?:switch|change|make\s+it|set\s+it\s+to)\s+to\s+(?:driving|walking|cycling|transit)""" +
            """|(?:switch|change)\s+(?:to\s+)?(?:driving|walking|cycling|transit)\s+(?:mode|directions?)?""",
            RegexOption.IGNORE_CASE
        )

        // Stop navigation — always match
        private val STOP_NAV = Regex(
            """stop\s+(?:the\s+)?(?:navigation|route|directions?)""" +
            """|end\s+(?:the\s+)?(?:route|navigation|directions?)""" +
            """|cancel\s+(?:the\s+)?(?:route|navigation|directions?)""",
            RegexOption.IGNORE_CASE
        )

        // Weak triggers — only match with active nav context
        private val WEAK_START = Regex(
            """(?:^|\s)(?:go|let'?s\s+go|go\s+now)(?:\s*$|\s*please)""" +
            """|(?:^|\s)start\s+it(?:\s*$|\s*please)""" +
            """|take\s+me\s+there""" +
            """|start\s+the\s+route""",
            RegexOption.IGNORE_CASE
        )

        // "close Maps" during navigation — context-guarded
        private val CLOSE_MAPS_NAV = Regex(
            """(?:close|exit|stop|quit)\s+(?:google\s+)?maps?""",
            RegexOption.IGNORE_CASE
        )

        // Extract travel mode from transcript
        fun parseMode(transcript: String): TravelMode {
            val t = transcript.lowercase()
            return when {
                "walk"     in t || "walking" in t || "foot"   in t -> TravelMode.WALKING
                "cycl"     in t || "bike"    in t || "bik"    in t -> TravelMode.BICYCLING
                "transit"  in t || " bus "   in t || " train" in t -> TravelMode.TRANSIT
                else                                                 -> TravelMode.DRIVING
            }
        }
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        val hasCtx = navContextStore.hasContext

        return when {
            STOP_NAV.containsMatchIn(t) -> {
                ToolInput(transcript, mapOf("action" to "stop"))
            }
            CLOSE_MAPS_NAV.containsMatchIn(t) && hasCtx -> {
                ToolInput(transcript, mapOf("action" to "stop"))
            }
            STRONG_START.containsMatchIn(t) -> {
                val mode = parseMode(t)
                ToolInput(transcript, mapOf("action" to "start", "mode" to mode.name))
            }
            MODE_SWITCH.containsMatchIn(t) -> {
                val mode = parseMode(t)
                ToolInput(transcript, mapOf("action" to "switch_mode", "mode" to mode.name))
            }
            WEAK_START.containsMatchIn(t) && hasCtx -> {
                // Preserve existing mode from context, but allow transcript override
                val mode = parseMode(t).let { parsed ->
                    if (parsed != TravelMode.DRIVING) parsed
                    else navContextStore.current?.mode ?: TravelMode.DRIVING
                }
                ToolInput(transcript, mapOf("action" to "start", "mode" to mode.name))
            }
            else -> null
        }
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val action = input.param("action")
        val modeArg = runCatching { TravelMode.valueOf(input.param("mode")) }
            .getOrDefault(TravelMode.DRIVING)

        Log.d(TAG, "[MAPS_NAV_START_REQUEST] action=$action mode=$modeArg")

        return when (action) {
            "start"       -> handleStart(modeArg)
            "switch_mode" -> handleSwitchMode(modeArg)
            "stop"        -> handleStop()
            else          -> handleStart(modeArg)
        }
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private fun handleStart(requestedMode: TravelMode): ToolResult {
        val ctx = navContextStore.current
        val destination = ctx?.destination
            ?: return ToolResult.Failure(
                "I'm not sure where you want to go. Try 'navigate to Tesco' first."
            )

        val mode = requestedMode
        Log.d(TAG, "[MAPS_NAV_MODE_RESOLVED] dest=$destination mode=$mode")

        val uri = mapsIntents.navigationUri(destination, mode)
        Log.d(TAG, "[MAPS_NAV_URI_LAUNCH] uri=$uri")

        val launched = mapsIntents.open(uri)

        return if (launched) {
            // Update context with confirmed mode
            navContextStore.update(ctx.copy(mode = mode, routeLoadedAt = System.currentTimeMillis()))
            val modeWord = when (mode) {
                TravelMode.DRIVING   -> "driving"
                TravelMode.WALKING   -> "walking"
                TravelMode.BICYCLING -> "cycling"
                TravelMode.TRANSIT   -> "transit"
            }
            Log.d(TAG, "[MAPS_NAV_SUCCESS] mode=$modeWord dest=$destination")
            ToolResult.Success("Starting $modeWord directions.")
        } else {
            Log.d(TAG, "[MAPS_NAV_FAILED] Maps not installed or intent rejected")
            ToolResult.Failure("Couldn't open Google Maps. Is it installed?")
        }
    }

    private fun handleSwitchMode(newMode: TravelMode): ToolResult {
        val ctx = navContextStore.current
            ?: return ToolResult.Failure(
                "No active route to switch. Start navigation first."
            )

        Log.d(TAG, "[MAPS_NAV_MODE_RESOLVED] switching to $newMode for dest=${ctx.destination}")
        return handleStart(newMode)
    }

    private fun handleStop(): ToolResult {
        navContextStore.clear()
        Log.d(TAG, "[MAPS_NAV_SUCCESS] stopped navigation")

        // HOME intent takes user out of Maps turn-by-turn
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "HOME intent failed during nav stop: ${e.message}")
        }

        return ToolResult.Success("Route stopped.")
    }
}
