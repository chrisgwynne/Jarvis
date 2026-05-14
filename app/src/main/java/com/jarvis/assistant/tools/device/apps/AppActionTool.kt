package com.jarvis.assistant.tools.device.apps

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * AppActionTool — registered Tool that handles app-aware utterances
 * via [AppActionParser] + [AppCapabilityRegistry] and dispatches the
 * resulting [AppActionParser.AppAction] through Android intents.
 *
 * Registered BEFORE the generic OpenAppTool so parameterised forms
 * ("open Firefox and search for X") win over the bare "open X" path.
 * Anything OpenAppTool already handles continues to work — this tool
 * declines to match when no AppCapability is found for the named app.
 *
 * Intents produced:
 *
 *   Open       — getLaunchIntentForPackage(packageName)
 *   Search     — search URL template + setPackage(packageName), or
 *                ACTION_WEB_SEARCH when the cap has no template.
 *   WebSearch  — ACTION_WEB_SEARCH with EXTRA_QUERY.
 *
 * No remote routing — strictly local.
 */
class AppActionTool(private val context: Context) : Tool {

    override val name = "app_action"
    override val description = "Open an app, optionally with a search query, via Android intents."
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions = emptyList<String>()

    companion object { private const val TAG = "AppActionTool" }

    override fun matches(transcript: String): ToolInput? {
        // The parser returns null for bare unknown openings ("open the
        // door") so we deliberately fall through to OpenAppTool when
        // no capability matched.  Carry the parsed action through the
        // input params for execute() — saves a second parse pass.
        val action = AppActionParser.parse(transcript) ?: return null
        val params = when (action) {
            is AppActionParser.AppAction.Open ->
                mapOf("kind" to "open", "package" to action.cap.packageName,
                      "app" to action.cap.displayName)
            is AppActionParser.AppAction.Search ->
                mapOf("kind" to "search",
                      "package" to action.cap.packageName,
                      "app" to action.cap.displayName,
                      "query" to action.query)
            is AppActionParser.AppAction.WebSearch ->
                mapOf("kind" to "web_search", "query" to action.query)
        }
        return ToolInput(transcript, params)
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Open an app, optionally with a search query, via Android intents. No remote routing.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "kind"    to mapOf("type" to "string",
                    "enum" to listOf("open", "search", "web_search")),
                "package" to mapOf("type" to "string"),
                "app"     to mapOf("type" to "string"),
                "query"   to mapOf("type" to "string"),
            ),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val kind = input.param("kind").ifBlank { "open" }
        return when (kind) {
            "open"        -> executeOpen(input)
            "search"      -> executeSearch(input)
            "web_search"  -> executeWebSearch(input)
            else          -> ToolResult.Failure("I didn't understand that app action.")
        }
    }

    private fun executeOpen(input: ToolInput): ToolResult {
        val pkg   = input.param("package")
        val appName = input.param("app").ifBlank { pkg }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ToolResult.Failure("$appName isn't installed.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.d(TAG, "[APP_ACTION_EXECUTE] kind=open app=$appName package=$pkg")
            Log.d(TAG, "[APP_ACTION_SUCCESS] app=$appName")
            ToolResult.Success("Opening $appName.")
        } catch (e: Exception) {
            Log.w(TAG, "[APP_ACTION_FAILED] $appName: ${e.message}")
            ToolResult.Failure("I couldn't open $appName.")
        }
    }

    private fun executeSearch(input: ToolInput): ToolResult {
        val pkg     = input.param("package")
        val appName = input.param("app").ifBlank { pkg }
        val query   = input.param("query")
        if (query.isBlank()) return executeOpen(input)

        val cap = AppCapabilityRegistry.ENTRIES.firstOrNull { it.packageName == pkg }
            ?: return executeOpen(input)
        val url = AppCapabilityRegistry.searchUrl(cap, query)
            ?: return ACTION_WEB_SEARCH_INTENT(query, appName, pkg)

        return try {
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // For geo: / whatsapp:// schemes the package set isn't
                // required, but it doesn't hurt.  For http(s) it pins
                // the search inside the user's named app instead of
                // their default browser.
                if (cap.primaryScheme == null) setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
            Log.d(TAG, "[APP_ACTION_EXECUTE] kind=search app=$appName " +
                "query=\"${query.take(40)}\"")
            Log.d(TAG, "[APP_ACTION_SUCCESS] app=$appName kind=search")
            ToolResult.Success("Searching $appName for $query.")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "[APP_ACTION_FAILED] $appName not installed: ${e.message}")
            ACTION_WEB_SEARCH_INTENT(query, appName, pkg = null)
        } catch (e: Exception) {
            Log.w(TAG, "[APP_ACTION_FAILED] $appName: ${e.message}")
            ToolResult.Failure("I couldn't search $appName.")
        }
    }

    private fun executeWebSearch(input: ToolInput): ToolResult {
        val query = input.param("query")
        if (query.isBlank()) return ToolResult.Failure("What should I search for?")
        return ACTION_WEB_SEARCH_INTENT(query, appName = "the web", pkg = null)
    }

    /** Fallback that goes through the Android-default web-search handler. */
    @Suppress("FunctionName")
    private fun ACTION_WEB_SEARCH_INTENT(
        query: String, appName: String, pkg: String?,
    ): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                pkg?.let { setPackage(it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "[APP_ACTION_EXECUTE] kind=web_search query=\"${query.take(40)}\"")
            Log.d(TAG, "[APP_ACTION_SUCCESS] kind=web_search app=$appName")
            ToolResult.Success("Searching $appName for $query.")
        } catch (e: Exception) {
            Log.w(TAG, "[APP_ACTION_FAILED] web search: ${e.message}")
            ToolResult.Failure("I couldn't open a web search.")
        }
    }
}

private typealias ActivityNotFoundException = android.content.ActivityNotFoundException
