package com.jarvis.assistant.intent

/**
 * The grab-bag of environmental signals used to resolve the word "this" in a
 * spoken command. Every slot is nullable so [KeywordIntentRouter] can operate
 * headless in tests without wiring a live Accessibility service or clipboard.
 *
 * Field order matches the JSON schema documented on the router so the
 * envelope serialisation stays stable as the schema evolves.
 */
data class ResolvedContext(
    val selectedText:        String?,
    val currentInputText:    String?,
    val clipboardText:       String?,
    val foregroundApp:       String?,
    val visibleUrl:          String?,
    val lastScreenshotPath:  String?,
    val lastUiEvent:         String?,
) {
    companion object {
        /** Empty context — used when all sources are unavailable. */
        val EMPTY = ResolvedContext(
            selectedText        = null,
            currentInputText    = null,
            clipboardText       = null,
            foregroundApp       = null,
            visibleUrl          = null,
            lastScreenshotPath  = null,
            lastUiEvent         = null,
        )
    }
}

/**
 * The "this" word is spoken constantly in voice commands. [ContextResolver]
 * picks the first non-blank slot in this priority order, then returns a
 * [ReferentResolution] pointing at which slot was chosen. Downstream
 * handlers read [text] for the content and [source] for provenance (so e.g.
 * a "reply to this" handler knows whether to compose against an input-field
 * draft vs. a clipboard paste vs. a screenshot).
 */
data class ReferentResolution(
    /** One of: selected_text, current_input_text, clipboard_text, last_screenshot_path, foreground_app, previous_turn, none. */
    val source: String,
    /** The text payload, or null when the chosen slot carries a non-text reference (e.g. a screenshot path). */
    val text: String?,
)
