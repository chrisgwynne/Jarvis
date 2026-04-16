package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult

/**
 * OpenAppTool — launches an installed app from a spoken name.
 *
 * Resolution uses [AppResolver] (learned alias → built-in alias → label fuzzy
 * → package-name → category intent).  Successful launches are fed back to the
 * alias store so recurring spoken forms bypass label scanning next time.
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

    override fun matches(transcript: String): ToolInput? {
        val m = REGEX.find(transcript.trim()) ?: return null
        val appName = m.groupValues[1].trim()

        // For "play X": only claim this command if resolution would succeed.
        if (PLAY_RE.containsMatchIn(transcript.trim())) {
            if (resolver.resolve(appName) is AppResolver.Result.NotFound) return null
        }

        return ToolInput(transcript, mapOf("app" to appName))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val appName = input.param("app")
        val result  = resolver.resolve(appName)

        return try {
            when (result) {
                is AppResolver.Result.Launchable -> {
                    val intent = context.packageManager
                        .getLaunchIntentForPackage(result.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?: return ToolResult.Failure(
                            "Found ${result.displayLabel} but couldn't open it — it might be suspended or need an update."
                        )
                    context.startActivity(intent)
                    // Persist confirmed alias only after the launch succeeded
                    resolver.rememberAlias(appName, result)
                    ToolResult.Success(spokenFeedback = "", silent = true)
                }
                is AppResolver.Result.GenericIntent -> {
                    result.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(result.intent)
                    ToolResult.Success(spokenFeedback = "", silent = true)
                }
                AppResolver.Result.NotFound -> {
                    ToolResult.Failure("I don't see $appName on your phone — is it installed? Try saying the exact name from your apps list.")
                }
            }
        } catch (e: Exception) {
            ToolResult.Failure("That didn't work — ${e.message ?: "couldn't launch the app"}.")
        }
    }
}
