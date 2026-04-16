package com.jarvis.assistant.tools

import android.net.Uri
import android.util.Log
import com.jarvis.assistant.llm.NetworkClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebSearch — fetches real-time information to inject into the LLM prompt.
 *
 * PRIMARY:  Brave Search API (requires free key from search.brave.com/settings)
 *           Returns actual web results — titles + snippets.
 *
 * FALLBACK: DuckDuckGo Instant Answers API (no key, free, limited to
 *           Wikipedia abstracts and direct-answer facts).
 *
 * The combined summary is prepended to the user's query before the LLM call,
 * so Jarvis can answer questions about current events, weather, stock prices, etc.
 */
class WebSearch {

    companion object {
        private const val TAG = "WebSearch"
        private const val DDG_URL = "https://api.duckduckgo.com/"
        private const val BRAVE_URL = "https://api.search.brave.com/res/v1/web/search"
    }

    /**
     * Fetch a search summary for [query].
     * [braveApiKey] — optional. If blank, falls back to DuckDuckGo.
     * Returns a formatted string ready to inject, or empty if nothing useful found.
     */
    suspend fun search(query: String, braveApiKey: String = ""): String {
        return try {
            if (braveApiKey.isNotBlank()) {
                braveSearch(query, braveApiKey).ifBlank { ddgSearch(query) }
            } else {
                ddgSearch(query)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Search failed: ${e.message}")
            ""
        }
    }

    // ── Brave Search ──────────────────────────────────────────────────────────

    private suspend fun braveSearch(query: String, apiKey: String): String {
        val url = "$BRAVE_URL?q=${Uri.encode(query)}&count=3&text_decorations=false"
        val body = NetworkClient.get(
            url     = url,
            headers = mapOf(
                "Accept"                to "application/json",
                "X-Subscription-Token" to apiKey
            )
        )

        val json = NetworkClient.gson.fromJson(body, BraveResponse::class.java)
        val results = json.web?.results?.take(3) ?: return ""

        if (results.isEmpty()) return ""

        return buildString {
            append("[Web search results for \"$query\"]\n")
            results.forEach { r ->
                append("• ${r.title}: ${r.description}\n")
            }
        }.trim()
    }

    // ── DuckDuckGo Instant Answers ─────────────────────────────────────────────

    private suspend fun ddgSearch(query: String): String {
        val url = "$DDG_URL?q=${Uri.encode(query)}&format=json&no_html=1&skip_disambig=1&t=jarvis"
        val body = NetworkClient.get(url = url, headers = mapOf("Accept" to "application/json"))

        val json = NetworkClient.gson.fromJson(body, DdgResponse::class.java)

        val parts = mutableListOf<String>()
        json.Answer?.takeIf { it.isNotBlank() }?.let { parts += it }
        json.AbstractText?.takeIf { it.isNotBlank() }?.let { parts += it }
        json.RelatedTopics
            ?.take(2)
            ?.mapNotNull { it.Text?.takeIf { t -> t.isNotBlank() } }
            ?.forEach { parts += it }

        if (parts.isEmpty()) return ""

        return buildString {
            append("[Search context for \"$query\"]\n")
            parts.forEach { append("• $it\n") }
        }.trim()
    }

    // ── Wire-format data classes ──────────────────────────────────────────────

    private data class DdgResponse(
        val Answer: String?,
        val AbstractText: String?,
        val RelatedTopics: List<DdgTopic>?
    )
    private data class DdgTopic(val Text: String?)

    private data class BraveResponse(val web: BraveWeb?)
    private data class BraveWeb(val results: List<BraveResult>?)
    private data class BraveResult(val title: String?, val description: String?)
}
