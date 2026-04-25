package com.jarvis.assistant.intent

import org.json.JSONArray
import org.json.JSONObject

/**
 * CommandEnvelope — canonical, fully-resolved spoken command.
 *
 * Produced by [KeywordIntentRouter.route]. Consumers downstream (dialog
 * controller, plan runner, handler bus) read the envelope without touching
 * the raw transcript — the envelope is the single source of truth for
 * everything that happens next.
 *
 * JSON SCHEMA (see [toJson]):
 *   {
 *     "raw_text":              string,
 *     "primary_intent":        string,
 *     "modifiers":             [string],
 *     "label":                 string | null,   // populated when "save this as {label}" fires
 *     "confidence":            number,          // 0.0–1.0
 *     "resolved_context": {
 *       "selected_text":        string | null,
 *       "current_input_text":   string | null,
 *       "clipboard_text":       string | null,
 *       "foreground_app":       string | null,
 *       "visible_url":          string | null,
 *       "last_screenshot_path": string | null,
 *       "last_ui_event":        string | null
 *     },
 *     "risk_level":            "low" | "medium" | "high",
 *     "requires_confirmation": boolean
 *   }
 */
data class CommandEnvelope(
    val rawText:              String,
    val primaryIntent:        PrimaryIntent,
    val modifiers:            List<IntentModifier>,
    /** Free-form label from "save this as {label}"; null unless LABEL_PROVIDED is set. */
    val label:                String?,
    val confidence:           Double,
    val resolvedContext:      ResolvedContext,
    val riskLevel:            RiskLevel,
    val requiresConfirmation: Boolean,
) {
    /**
     * Serialise to a JSON object matching the documented schema.  Uses
     * org.json.JSONObject so the wire format lines up with the rest of the
     * codebase (VisionClient, ScreenObservation, etc.).
     */
    fun toJson(): JSONObject {
        val ctx = JSONObject().apply {
            put("selected_text",        resolvedContext.selectedText        ?: JSONObject.NULL)
            put("current_input_text",   resolvedContext.currentInputText    ?: JSONObject.NULL)
            put("clipboard_text",       resolvedContext.clipboardText       ?: JSONObject.NULL)
            put("foreground_app",       resolvedContext.foregroundApp       ?: JSONObject.NULL)
            put("visible_url",          resolvedContext.visibleUrl          ?: JSONObject.NULL)
            put("last_screenshot_path", resolvedContext.lastScreenshotPath  ?: JSONObject.NULL)
            put("last_ui_event",        resolvedContext.lastUiEvent         ?: JSONObject.NULL)
        }
        val mods = JSONArray().apply { modifiers.forEach { put(it.name) } }
        return JSONObject().apply {
            put("raw_text",              rawText)
            put("primary_intent",        primaryIntent.name)
            put("modifiers",             mods)
            put("label",                 label ?: JSONObject.NULL)
            put("confidence",            confidence)
            put("resolved_context",      ctx)
            put("risk_level",            riskLevel.name.lowercase())
            put("requires_confirmation", requiresConfirmation)
        }
    }
}
