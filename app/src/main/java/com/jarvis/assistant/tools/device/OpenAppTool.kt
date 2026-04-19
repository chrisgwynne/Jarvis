package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

/**
 * OpenAppTool — launches an installed app from a spoken name.
 *
 * Resolution uses [AppResolver] (learned alias → built-in alias → label fuzzy
 * → package-name → category intent).  Successful launches are fed back to the
 * alias store so recurring spoken forms bypass label scanning next time.
 *
 * LOW-CONFIDENCE MATCHES (broad substring hits) are not launched blind. Jarvis
 * asks "Did you mean X?" once and waits for a yes/no on the next turn. On yes
 * the alias is persisted and the app launches; on no the pending match is
 * discarded.  HIGH-confidence matches (alias maps, exact/prefix label) launch
 * straight away.
 */
class OpenAppTool(
    private val context: Context,
    private val resolver: AppResolver = AppResolver(context, AppAliasStore(context))
) : Tool {

    override val name = "open_app"
    override val description = "Open an installed app by name"

    // "play X" is included so "play spotify" works, but we only claim the
    // command if the named app actually resolves — this lets "play some jazz"
    // fall through to the LLM instead of failing.
    private val REGEX   = Regex("""(?:open|launch|start|play)\s+(.+)""", RegexOption.IGNORE_CASE)
    private val PLAY_RE = Regex("""^play\s+""", RegexOption.IGNORE_CASE)
    // Short-confirmation regexes: deliberately anchored and with no trailing
    // clause, because words like "right", "sure", "ok" show up at the start
    // of many commands ("right now, open maps", "sure, set a timer at 5").
    // Treating those as a yes to a pending "Did you mean …?" would hijack
    // the real command.  We only claim confirmations when the whole utterance
    // is one of these short forms.
    private val YES_RE  = Regex("""^\s*(yes|yeah|yep|yup|correct|sure|ok(?:ay)?|do it|go ahead|please do|that's it|thats it|right one)\s*\.?\s*$""", RegexOption.IGNORE_CASE)
    private val NO_RE   = Regex("""^\s*(no|nope|nah|cancel|don't|dont|never mind|nevermind|wrong one|not that)\s*\.?\s*$""", RegexOption.IGNORE_CASE)

    override fun matches(transcript: String): ToolInput? {
        val trimmed = transcript.trim()

        // Confirmation path — only claim if there's a pending "Did you mean …?"
        // and the new utterance is a short yes/no (≤4 words) so we don't
        // swallow commands that happen to start with "right" or "sure".
        if (pendingConfirmation != null && trimmed.split(Regex("\\s+")).size <= 4) {
            if (YES_RE.matches(trimmed)) {
                return ToolInput(transcript, mapOf("confirm" to "yes"))
            }
            if (NO_RE.matches(trimmed)) {
                return ToolInput(transcript, mapOf("confirm" to "no"))
            }
        }

        val m = REGEX.find(trimmed) ?: return null
        val appName = m.groupValues[1].trim()

        // For "play X": only claim this command if resolution would succeed.
        if (PLAY_RE.containsMatchIn(trimmed)) {
            if (resolver.resolve(appName) is AppResolver.Result.NotFound) return null
        }

        return ToolInput(transcript, mapOf("app" to appName))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        input.paramOrNull("confirm")?.let { return handleConfirmation(it) }

        val appName = input.param("app")
        val result  = resolver.resolve(appName)

        return try {
            when (result) {
                is AppResolver.Result.Launchable -> {
                    if (result.confidence == AppResolver.Confidence.LOW) {
                        pendingConfirmation = Pending(
                            spokenName  = appName,
                            packageName = result.packageName,
                            label       = result.displayLabel
                        )
                        return ToolResult.Success(
                            spokenFeedback = "Did you mean ${result.displayLabel}?"
                        )
                    }
                    launchPackage(result.packageName, result.displayLabel, appName)
                }
                is AppResolver.Result.GenericIntent -> {
                    result.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(result.intent)
                    ToolResult.Success(spokenFeedback = "Opening ${result.displayLabel}.")
                }
                AppResolver.Result.NotFound -> {
                    ToolResult.Failure("${appName.trim().replaceFirstChar { it.uppercase() }} isn't installed on this phone.")
                }
            }
        } catch (e: Exception) {
            Log.w("OpenAppTool", "Launch failed", e)
            ToolResult.Failure("That didn't work.")
        }
    }

    private fun handleConfirmation(answer: String): ToolResult {
        val pending = pendingConfirmation
            ?: return ToolResult.Success(spokenFeedback = "")
        pendingConfirmation = null

        if (answer == "no") {
            return ToolResult.Success(spokenFeedback = "OK.")
        }

        return try {
            val intent = context.packageManager
                .getLaunchIntentForPackage(pending.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return ToolResult.Failure(
                    "${pending.label} isn't installed on this phone."
                )
            context.startActivity(intent)
            resolver.rememberAlias(
                pending.spokenName,
                AppResolver.Result.Launchable(pending.packageName, pending.label)
            )
            ToolResult.Success(spokenFeedback = "Opening ${pending.label}.")
        } catch (e: Exception) {
            Log.w("OpenAppTool", "Launch failed", e)
            ToolResult.Failure("That didn't work.")
        }
    }

    private fun launchPackage(packageName: String, label: String, spokenName: String): ToolResult {
        val intent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ToolResult.Failure(
                "Found $label but couldn't open it — it might be suspended or need an update."
            )
        context.startActivity(intent)
        resolver.rememberAlias(spokenName, AppResolver.Result.Launchable(packageName, label))
        return ToolResult.Success(spokenFeedback = "Opening $label.")
    }

    private data class Pending(
        val spokenName : String,
        val packageName: String,
        val label      : String
    )

    /**
     * Cross-turn state for "Did you mean …?" confirmation flow.  Volatile
     * because match()/execute() may be called from different coroutine
     * dispatchers.  Scoped to the instance — the runtime holds a single
     * OpenAppTool so process-lifetime semantics are preserved, while tests
     * and any future per-session instances no longer share state.
     */
    @Volatile
    private var pendingConfirmation: Pending? = null
}
