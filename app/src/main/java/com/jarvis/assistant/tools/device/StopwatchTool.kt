package com.jarvis.assistant.tools.device

import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * StopwatchTool — pure-local in-memory stopwatch.  "start the stopwatch",
 * "stop the stopwatch", "lap", "reset the stopwatch", "what's the stopwatch
 * at" / "how long has the stopwatch been running".
 *
 * Single-instance state (one stopwatch per device — the voice surface is not
 * a UI for managing multiple timers).  TimerTool covers count-down; this is
 * count-up.
 */
class StopwatchTool(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Tool {

    override val name = "stopwatch"
    override val description = "Start, stop, lap, reset, or check the stopwatch."
    override val requiresNetwork = false
    override val requiredPermissions = emptyList<String>()

    /** Action verb extracted from the transcript. */
    private enum class Verb { START, STOP, LAP, RESET, READ }

    private data class State(
        val running: Boolean = false,
        /** Wall-clock time when the current run started.  Only valid when [running]. */
        val startedAtMs: Long = 0L,
        /** Total accumulated time across previous start/stop cycles. */
        val accumulatedMs: Long = 0L,
        val laps: List<Long> = emptyList(),
    )

    @Volatile private var state = State()

    companion object {
        private val START_RX = Regex(
            """\b(?:start|begin|kick\s*off|fire\s*up)\s+(?:the\s+|a\s+)?stop\s*watch\b""",
            RegexOption.IGNORE_CASE,
        )
        private val STOP_RX = Regex(
            """\b(?:stop|pause|halt|end)\s+(?:the\s+)?stop\s*watch\b""",
            RegexOption.IGNORE_CASE,
        )
        private val LAP_RX = Regex(
            """\b(?:lap|mark\s+(?:a\s+)?lap|stopwatch\s+lap)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val RESET_RX = Regex(
            """\b(?:reset|clear|zero)\s+(?:the\s+)?stop\s*watch\b""",
            RegexOption.IGNORE_CASE,
        )
        private val READ_RX = Regex(
            """\b(?:what(?:'?s|\s+is)|how\s+long|where\s+is)\s+(?:the\s+)?stop\s*watch\s*(?:at|been|running|now)?\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        val verb = when {
            START_RX.containsMatchIn(t) -> Verb.START
            STOP_RX.containsMatchIn(t)  -> Verb.STOP
            RESET_RX.containsMatchIn(t) -> Verb.RESET
            LAP_RX.containsMatchIn(t)   -> Verb.LAP
            READ_RX.containsMatchIn(t)  -> Verb.READ
            else -> return null
        }
        return ToolInput(transcript, mapOf("verb" to verb.name))
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Control the stopwatch.  Verb is one of start/stop/lap/reset/read.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "verb" to mapOf(
                    "type" to "string",
                    "enum" to listOf("START", "STOP", "LAP", "RESET", "READ"),
                ),
            ),
            "required" to listOf("verb"),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val verb = runCatching { Verb.valueOf(input.param("verb")) }.getOrNull()
            ?: return ToolResult.Failure("I didn't catch what you wanted to do with the stopwatch.")
        val now = clock()
        return when (verb) {
            Verb.START -> {
                if (state.running) {
                    ToolResult.Success("Stopwatch is already running — at ${format(elapsedMs(now))}.")
                } else {
                    state = state.copy(running = true, startedAtMs = now)
                    ToolResult.Success("Stopwatch started.")
                }
            }
            Verb.STOP -> {
                if (!state.running) {
                    ToolResult.Success("Stopwatch is already stopped — at ${format(state.accumulatedMs)}.")
                } else {
                    val total = elapsedMs(now)
                    state = state.copy(running = false, accumulatedMs = total, startedAtMs = 0L)
                    ToolResult.Success("Stopwatch stopped at ${format(total)}.")
                }
            }
            Verb.LAP -> {
                val t = elapsedMs(now)
                state = state.copy(laps = state.laps + t)
                ToolResult.Success("Lap ${state.laps.size}: ${format(t)}.")
            }
            Verb.RESET -> {
                state = State()
                ToolResult.Success("Stopwatch reset.")
            }
            Verb.READ -> {
                val t = elapsedMs(now)
                ToolResult.Success(
                    if (state.running) "Stopwatch is at ${format(t)}."
                    else "Stopwatch is stopped at ${format(t)}.",
                )
            }
        }
    }

    internal fun elapsedMs(now: Long): Long =
        if (state.running) state.accumulatedMs + (now - state.startedAtMs)
        else state.accumulatedMs

    private fun format(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else  -> "${s}s"
        }
    }

    // Test-only seam.
    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() { state = State() }
}
