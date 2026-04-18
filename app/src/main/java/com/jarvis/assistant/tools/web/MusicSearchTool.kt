package com.jarvis.assistant.tools.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * MusicSearchTool — searches for and plays specific tracks, artists, or genres.
 *
 * Fast path: Spotify URI scheme (spotify:search:QUERY) — opens Spotify search directly,
 * no OAuth or API key required.
 * Fallback: YouTube Music deep-link intent if Spotify is not installed.
 *
 * Must be registered BEFORE MediaControlTool in ToolRegistry so "play Bohemian Rhapsody"
 * routes here instead of the generic play/pause handler.
 *
 * Guard: bare "play", "pause", "stop", "resume", "skip", "next", "previous" are
 * NOT matched — those belong to MediaControlTool.
 */
class MusicSearchTool(private val context: Context) : Tool {

    override val name            = "music_search"
    override val description     = "Search for and play a specific song, artist, or genre"
    override val requiresNetwork = true

    override fun schema() = ToolSchema(
        name        = name,
        description = "Search for music by song, artist, or genre and play it.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query"  to mapOf("type" to "string", "description" to "Full search query, e.g. 'Bohemian Rhapsody by Queen'"),
                "artist" to mapOf("type" to "string", "description" to "Artist name if specified separately")
            ),
            "required" to listOf("query")
        )
    )

    private val PLAY_REGEX = Regex(
        """^play\s+(?:some\s+|me\s+)?(.+?)(?:\s+by\s+(.+))?$""",
        RegexOption.IGNORE_CASE
    )

    // Words that alone should NOT be routed here (belong to MediaControlTool)
    private val MEDIA_CONTROL_ONLY = setOf(
        "play", "pause", "stop", "resume", "skip", "next", "previous",
        "play music", "play something", "pause music", "stop music",
        "resume music", "next song", "next track", "previous song"
    )

    override fun matches(transcript: String): ToolInput? {
        val trimmed = transcript.trim()
        if (trimmed.lowercase() in MEDIA_CONTROL_ONLY) return null

        val m = PLAY_REGEX.find(trimmed) ?: return null
        val trackPart  = m.groupValues[1].trim()
        val artistPart = m.groupValues.getOrElse(2) { "" }.trim()

        // Reject bare control words even if they passed the regex
        if (trackPart.lowercase() in setOf("it", "that", "music", "something", "anything")) return null

        val query = if (artistPart.isNotBlank()) "$trackPart $artistPart" else trackPart
        return ToolInput(trimmed, mapOf("query" to query, "artist" to artistPart))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val query = input.param("query").ifBlank { return ToolResult.Failure("No search query provided.") }

        // Try Spotify first
        return try {
            val spotifyUri = Uri.parse("spotify:search:${Uri.encode(query)}")
            context.startActivity(
                Intent(Intent.ACTION_VIEW, spotifyUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ToolResult.Success("Searching for $query on Spotify.")
        } catch (e: ActivityNotFoundException) {
            // Spotify not installed — fall back to YouTube Music
            try {
                val ytUri = Uri.parse("https://music.youtube.com/search?q=${Uri.encode(query)}")
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, ytUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                ToolResult.Success("Searching for $query on YouTube Music.")
            } catch (e2: Exception) {
                ToolResult.Failure("Couldn't open a music app: ${e2.message}")
            }
        }
    }
}
