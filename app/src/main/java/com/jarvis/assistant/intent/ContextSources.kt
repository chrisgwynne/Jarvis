package com.jarvis.assistant.intent

/**
 * ContextSources — the interface the host app (Android service layer) must
 * implement to expose live UI / clipboard / accessibility state to the
 * intent router.
 *
 * Each getter is a one-shot snapshot call — implementations must return
 * quickly (no IO), because [ContextResolver] calls every slot on every
 * utterance. Return null for any slot that is unavailable; the resolver
 * treats null and blank identically.
 *
 * DESIGN:
 *   We keep this as a plain interface (not a class) so tests can supply a
 *   lambda-driven stub without touching Android framework classes.
 */
interface ContextSources {
    /** Currently selected text in the foreground editable field. */
    fun selectedText(): String?

    /** Full text of the currently focused input field (often the draft the user is composing). */
    fun currentInputText(): String?

    /** System clipboard contents (may be null if permission denied on Android 10+). */
    fun clipboardText(): String?

    /** Package name or human-readable label of the foreground app. */
    fun foregroundApp(): String?

    /** The URL shown in the foreground browser tab / in-app WebView, if any. */
    fun visibleUrl(): String?

    /** Absolute path to the most recent screenshot captured by Jarvis. */
    fun lastScreenshotPath(): String?

    /** Short description of the most recent UI event (e.g. "Gmail thread opened"). */
    fun lastUiEvent(): String?

    /** Summary of the previous assistant / user turn, for "this" fallback when no UI signal is available. */
    fun previousTurnContext(): String? = null
}
