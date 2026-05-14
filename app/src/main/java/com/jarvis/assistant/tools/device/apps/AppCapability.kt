package com.jarvis.assistant.tools.device.apps

/**
 * AppCapability — declarative description of what each known app can
 * do via Android intents.
 *
 * One entry per app.  The registry lookup is by [names] (any token
 * the user might use) → exact match against the spoken phrase OR a
 * substring match within a longer utterance.
 *
 * Capabilities are flagged as booleans rather than enums so we can
 * grow the set without breaking JSON serialisation later (registry
 * persistence is a future concern).
 *
 * **Deep links and search URLs.**
 * The capability registry intentionally stores user-visible search
 * URL templates (`https://www.google.com/search?q=...`) rather than
 * proprietary deep-link schemes — those break across app versions.
 * Apps that DO have stable schemes (whatsapp://, geo:) carry them in
 * [primaryScheme].
 */
data class AppCapability(
    /** Display name for diagnostics / spoken confirmation. */
    val displayName: String,
    /** All names the user might use, lowercase ("firefox", "browser", "web"). */
    val names: Set<String>,
    /** Android package id — used for `setPackage(...)` + `getLaunchIntentForPackage`. */
    val packageName: String,
    /** Capability category — used by the parser to disambiguate intents. */
    val category: Category,
    val supportsOpen: Boolean              = true,
    val supportsWebSearch: Boolean         = false,
    val supportsInAppSearch: Boolean       = false,
    val supportsNavigate: Boolean          = false,
    val supportsMediaPlayback: Boolean     = false,
    val supportsMessaging: Boolean         = false,
    /**
     * Optional deep-link template — uses `{q}` as the search-term
     * placeholder.  Examples:
     *   Google search:  "https://www.google.com/search?q={q}"
     *   Maps search:    "geo:0,0?q={q}"
     *   Etsy search:    "https://www.etsy.com/search?q={q}"
     */
    val searchUrlTemplate: String?         = null,
    /** Optional non-http scheme for direct intent dispatch (e.g. "whatsapp"). */
    val primaryScheme: String?             = null,
) {
    enum class Category { BROWSER, MAPS, GALLERY, MUSIC, MESSAGING, EMAIL, SHOPPING, SOCIAL, VIDEO, GENERIC }
}
