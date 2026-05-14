package com.jarvis.assistant.tools.device.apps

/**
 * AppCapabilityRegistry — central list of [AppCapability] entries for
 * the apps Jarvis routes commands into.
 *
 * Adding a new app: append an entry to [ENTRIES].  The registry is
 * static for now — persistence + user-editable entries can come later
 * without changing the lookup surface.
 *
 * Lookup is by friendly name (case-insensitive, normalised on
 * tokenisation).  `find("firefox")` and `find("browser")` both
 * resolve to the Firefox entry; `find("maps")` and `find("google
 * maps")` both resolve to Google Maps.
 *
 * Pure / Android-free / unit-testable.
 */
object AppCapabilityRegistry {

    /**
     * Canonical capability list.  Order is irrelevant to lookup but
     * grouped by category for readability.
     */
    val ENTRIES: List<AppCapability> = listOf(
        // ── Browsers ──────────────────────────────────────────────────
        AppCapability(
            displayName       = "Firefox",
            names             = setOf("firefox", "fox"),
            packageName       = "org.mozilla.firefox",
            category          = AppCapability.Category.BROWSER,
            supportsWebSearch = true,
            searchUrlTemplate = "https://www.google.com/search?q={q}",
        ),
        AppCapability(
            displayName       = "Chrome",
            names             = setOf("chrome", "google chrome", "browser", "web"),
            packageName       = "com.android.chrome",
            category          = AppCapability.Category.BROWSER,
            supportsWebSearch = true,
            searchUrlTemplate = "https://www.google.com/search?q={q}",
        ),
        AppCapability(
            displayName       = "DuckDuckGo",
            names             = setOf("duckduckgo", "ddg", "duck duck go"),
            packageName       = "com.duckduckgo.mobile.android",
            category          = AppCapability.Category.BROWSER,
            supportsWebSearch = true,
            searchUrlTemplate = "https://duckduckgo.com/?q={q}",
        ),

        // ── Maps ──────────────────────────────────────────────────────
        AppCapability(
            displayName         = "Google Maps",
            names               = setOf("maps", "google maps"),
            packageName         = "com.google.android.apps.maps",
            category            = AppCapability.Category.MAPS,
            supportsInAppSearch = true,
            supportsNavigate    = true,
            searchUrlTemplate   = "geo:0,0?q={q}",
            primaryScheme       = "geo",
        ),

        // ── Gallery / Photos ──────────────────────────────────────────
        AppCapability(
            displayName  = "Google Photos",
            names        = setOf("photos", "google photos", "gallery", "pictures"),
            packageName  = "com.google.android.apps.photos",
            category     = AppCapability.Category.GALLERY,
        ),

        // ── Music ─────────────────────────────────────────────────────
        AppCapability(
            displayName       = "Spotify",
            names             = setOf("spotify"),
            packageName       = "com.spotify.music",
            category          = AppCapability.Category.MUSIC,
            supportsMediaPlayback = true,
            supportsInAppSearch = true,
            searchUrlTemplate   = "https://open.spotify.com/search/{q}",
        ),
        AppCapability(
            displayName       = "YouTube Music",
            names             = setOf("youtube music", "ytm"),
            packageName       = "com.google.android.apps.youtube.music",
            category          = AppCapability.Category.MUSIC,
            supportsMediaPlayback = true,
        ),

        // ── Messaging / Social ────────────────────────────────────────
        AppCapability(
            displayName     = "WhatsApp",
            names           = setOf("whatsapp", "wa"),
            packageName     = "com.whatsapp",
            category        = AppCapability.Category.MESSAGING,
            supportsMessaging = true,
            primaryScheme   = "whatsapp",
        ),
        AppCapability(
            displayName     = "Messenger",
            names           = setOf("messenger", "facebook messenger"),
            packageName     = "com.facebook.orca",
            category        = AppCapability.Category.MESSAGING,
            supportsMessaging = true,
        ),
        AppCapability(
            displayName     = "Signal",
            names           = setOf("signal"),
            packageName     = "org.thoughtcrime.securesms",
            category        = AppCapability.Category.MESSAGING,
            supportsMessaging = true,
        ),
        AppCapability(
            displayName     = "Telegram",
            names           = setOf("telegram"),
            packageName     = "org.telegram.messenger",
            category        = AppCapability.Category.MESSAGING,
            supportsMessaging = true,
        ),

        // ── Email ─────────────────────────────────────────────────────
        AppCapability(
            displayName         = "Gmail",
            names               = setOf("gmail", "google mail"),
            packageName         = "com.google.android.gm",
            category            = AppCapability.Category.EMAIL,
            supportsInAppSearch = true,
            searchUrlTemplate   = "googlegmail://search?q={q}",
        ),

        // ── Video ─────────────────────────────────────────────────────
        AppCapability(
            displayName         = "YouTube",
            names               = setOf("youtube", "yt"),
            packageName         = "com.google.android.youtube",
            category            = AppCapability.Category.VIDEO,
            supportsInAppSearch = true,
            searchUrlTemplate   = "https://www.youtube.com/results?search_query={q}",
        ),

        // ── Shopping ──────────────────────────────────────────────────
        AppCapability(
            displayName         = "Etsy",
            names               = setOf("etsy"),
            packageName         = "com.etsy.android",
            category            = AppCapability.Category.SHOPPING,
            supportsInAppSearch = true,
            searchUrlTemplate   = "https://www.etsy.com/search?q={q}",
        ),
        AppCapability(
            displayName         = "eBay",
            names               = setOf("ebay"),
            packageName         = "com.ebay.mobile",
            category            = AppCapability.Category.SHOPPING,
            supportsInAppSearch = true,
            searchUrlTemplate   = "https://www.ebay.co.uk/sch/i.html?_nkw={q}",
        ),
        AppCapability(
            displayName         = "Amazon",
            names               = setOf("amazon"),
            packageName         = "com.amazon.mvm.mshop.android.shopping",
            category            = AppCapability.Category.SHOPPING,
            supportsInAppSearch = true,
            searchUrlTemplate   = "https://www.amazon.co.uk/s?k={q}",
        ),
    )

    /** Build a flat token → capability map for fast lookup. */
    private val byToken: Map<String, AppCapability> = buildMap {
        for (cap in ENTRIES) {
            for (name in cap.names) put(name.lowercase(), cap)
        }
    }

    /**
     * Find a capability by friendly name.  Returns null when the name
     * is unknown — caller falls back to the generic OpenAppTool /
     * package-name resolver.
     */
    fun find(name: String): AppCapability? = byToken[name.lowercase().trim()]

    /**
     * Find a capability whose name appears as a token in [transcript].
     * Useful for "open Firefox and search for X" — the parser pulls
     * "firefox" out of the middle of the sentence.
     */
    fun findInTranscript(transcript: String): AppCapability? {
        val tokens = transcript.lowercase().split(Regex("\\s+"))
        // Try multi-word names first ("google maps", "youtube music").
        for (cap in ENTRIES) {
            for (name in cap.names) {
                if (' ' in name && name in transcript.lowercase()) return cap
            }
        }
        for (t in tokens) {
            val cleaned = t.trim('.', ',', '!', '?', ':', ';', '"', '\'')
            byToken[cleaned]?.let { return it }
        }
        return null
    }

    /** Build the search-launch URL for a capability + query. */
    fun searchUrl(cap: AppCapability, query: String): String? {
        val tmpl = cap.searchUrlTemplate ?: return null
        return tmpl.replace("{q}", java.net.URLEncoder.encode(query, "UTF-8"))
    }
}
