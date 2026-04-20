package com.jarvis.assistant.intent

/**
 * ContextResolver — builds a [ResolvedContext] snapshot and resolves the
 * word "this" to a concrete referent.
 *
 * PRIORITY ORDER for "this":
 *   1. selected_text         — strongest signal; user physically highlighted it
 *   2. current_input_text    — draft the user is composing right now
 *   3. clipboard_text        — short-lived but user-driven
 *   4. last_screenshot_path  — non-text; handler decides how to use it
 *   5. foreground_app        — weak; used when nothing text-bearing is available
 *   6. previous_turn         — last-resort fallback from the conversation history
 *
 * The resolver does not inspect the raw transcript — callers interested in
 * "this" resolution call [resolveReferent] after getting the envelope.
 */
class ContextResolver(private val sources: ContextSources) {

    /**
     * Take a one-shot snapshot of every source. Blank strings are converted
     * to null so downstream consumers only have to null-check, not also
     * check blank.
     */
    fun snapshot(): ResolvedContext = ResolvedContext(
        selectedText        = sources.selectedText().nullIfBlank(),
        currentInputText    = sources.currentInputText().nullIfBlank(),
        clipboardText       = sources.clipboardText().nullIfBlank(),
        foregroundApp       = sources.foregroundApp().nullIfBlank(),
        visibleUrl          = sources.visibleUrl().nullIfBlank(),
        lastScreenshotPath  = sources.lastScreenshotPath().nullIfBlank(),
        lastUiEvent         = sources.lastUiEvent().nullIfBlank(),
    )

    /**
     * Pick the first populated slot in the priority order and return a
     * [ReferentResolution] describing which slot won. Falls back to
     * previous-turn context if no live slot carries signal.
     */
    fun resolveReferent(ctx: ResolvedContext): ReferentResolution {
        ctx.selectedText?.let        { return ReferentResolution("selected_text",        it) }
        ctx.currentInputText?.let    { return ReferentResolution("current_input_text",   it) }
        ctx.clipboardText?.let       { return ReferentResolution("clipboard_text",       it) }
        ctx.lastScreenshotPath?.let  { return ReferentResolution("last_screenshot_path", it) }
        ctx.foregroundApp?.let       { return ReferentResolution("foreground_app",       it) }
        sources.previousTurnContext().nullIfBlank()?.let {
            return ReferentResolution("previous_turn", it)
        }
        return ReferentResolution("none", null)
    }

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
}
