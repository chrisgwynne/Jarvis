package com.jarvis.assistant.tools.device.apps

/**
 * AppActionParser — recognise app-aware utterances that combine an
 * app name with a parameter (search term, destination, query) and
 * produce a structured [AppAction].
 *
 * Pure / Android-free.  Returns null when the utterance isn't an
 * app-aware command so callers fall through to the simpler
 * OpenAppTool.
 *
 * Supported forms:
 *
 *   Browser:
 *     "open Firefox and search for 3D printer filament"
 *     "search Firefox for Bambu slicer settings"
 *     "Google 3D printer nozzles"
 *     "search the web for Etsy SEO tips"
 *
 *   Maps:
 *     "open Maps"
 *     "navigate home"
 *     "take me to Tesco"
 *     "search Maps for petrol stations"
 *
 *   Shopping:
 *     "search Etsy for keyrings"
 *     "search eBay for sublimation blanks"
 *
 *   Video:
 *     "search YouTube for Bambu tutorials"
 *     "play YouTube"
 *
 *   Generic open:
 *     "open Spotify"
 *     "open WhatsApp"
 */
object AppActionParser {

    sealed class AppAction {
        /** Just open the app — no parameters. */
        data class Open(val cap: AppCapability) : AppAction()

        /** Open the app and run an in-app or web search for [query]. */
        data class Search(val cap: AppCapability, val query: String) : AppAction()

        /**
         * Generic web search — no specific app named.  Caller routes
         * to the default browser via ACTION_WEB_SEARCH.
         */
        data class WebSearch(val query: String) : AppAction()
    }

    /**
     * "open X and search for Y" / "search X for Y" / "X search for Y"
     * Group 1 = app name (optional via lookaround), group 2 = query.
     */
    private val OPEN_AND_SEARCH_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:open|launch|start)\s+([a-z][a-z0-9\-\s]{0,30}?)
        \s+and\s+search\s+(?:for\s+)?
        (.+?)
        \s*[.!?]?\s*$
        """,
    )

    private val SEARCH_APP_FOR_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        search\s+(?:in\s+)?([a-z][a-z0-9\-\s]{0,30}?)
        \s+for\s+
        (.+?)
        \s*[.!?]?\s*$
        """,
    )

    /** "Google X" as a verb. */
    private val GOOGLE_VERB_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?google\s+(.+?)\s*[.!?]?\s*$
        """,
    )

    /** Generic web search — no specific app. */
    private val WEB_SEARCH_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:search\s+(?:the\s+)?web|search\s+online|look\s+up)\s+(?:for\s+)?
        (.+?)
        \s*[.!?]?\s*$
        """,
    )

    /** Bare "open X" / "launch X" / "start X". */
    private val OPEN_BARE_RX = Regex(
        """(?ix)
        ^\s*(?:please\s+)?
        (?:open|launch|start)\s+
        (.+?)
        \s*[.!?]?\s*$
        """,
    )

    /** Cheap predicate to short-circuit when uninterested. */
    fun looksLikeAppCommand(transcript: String): Boolean {
        val t = transcript.trim()
        if (t.isBlank()) return false
        return OPEN_AND_SEARCH_RX.matches(t) ||
            SEARCH_APP_FOR_RX.matches(t) ||
            GOOGLE_VERB_RX.matches(t) ||
            WEB_SEARCH_RX.matches(t) ||
            OPEN_BARE_RX.matches(t)
    }

    /** Full parse — null = not an app-aware command. */
    fun parse(transcript: String): AppAction? {
        val t = transcript.trim()
        if (t.isBlank()) return null

        OPEN_AND_SEARCH_RX.find(t)?.let { m ->
            val appName = m.groupValues[1].trim()
            val query   = m.groupValues[2].trim()
            val cap = AppCapabilityRegistry.find(appName)
                ?: AppCapabilityRegistry.findInTranscript(appName)
                ?: return@let
            return AppAction.Search(cap, query)
        }

        SEARCH_APP_FOR_RX.find(t)?.let { m ->
            val appName = m.groupValues[1].trim()
            val query   = m.groupValues[2].trim()
            // "search the web for X" routes via WEB_SEARCH_RX, not here.
            if (appName.lowercase() in setOf("the web", "web", "online", "google")) return@let
            val cap = AppCapabilityRegistry.find(appName)
                ?: AppCapabilityRegistry.findInTranscript(appName)
                ?: return@let
            return AppAction.Search(cap, query)
        }

        GOOGLE_VERB_RX.find(t)?.let { m ->
            val query = m.groupValues[1].trim()
            // Prefer the browser entry (we Google INSIDE Chrome / Firefox).
            val cap = AppCapabilityRegistry.find("chrome")
                ?: AppCapabilityRegistry.find("firefox")
                ?: return AppAction.WebSearch(query)
            return AppAction.Search(cap, query)
        }

        WEB_SEARCH_RX.find(t)?.let { m ->
            val query = m.groupValues[1].trim()
            return AppAction.WebSearch(query)
        }

        OPEN_BARE_RX.find(t)?.let { m ->
            val appName = m.groupValues[1].trim()
            // Filter out everything that's clearly not an app name —
            // "open the door" / "open my eyes" etc.  We require a
            // registry hit OR a single-word noun.
            val cap = AppCapabilityRegistry.find(appName)
                ?: AppCapabilityRegistry.findInTranscript(appName)
                ?: return@let
            return AppAction.Open(cap)
        }

        return null
    }
}
