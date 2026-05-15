package com.jarvis.assistant.ui.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.SettingsViewModel
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsSliderRow
import com.jarvis.assistant.ui.settings.SettingsToggleRow

@Composable
internal fun VisionSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val visionEnabled              by vm.visionEnabled.collectAsStateWithLifecycle()
    val preferPhoneCamera          by vm.preferPhoneCamera.collectAsStateWithLifecycle()
    val visionAutoFallback         by vm.visionAutoFallbackToPhone.collectAsStateWithLifecycle()
    val screenshotAnalysis         by vm.screenshotAnalysisEnabled.collectAsStateWithLifecycle()
    val screenshotListener         by vm.screenshotListenerEnabled.collectAsStateWithLifecycle()
    val ocrEnabled                 by vm.ocrEnabled.collectAsStateWithLifecycle()
    val saveVisualContext          by vm.saveVisualContext.collectAsStateWithLifecycle()
    val retentionDays              by vm.visualMemoryRetentionDays.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Vision", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Phone camera first",
            body  = "Jarvis uses your phone camera and screenshots as the primary vision " +
                    "path. Meta glasses, if connected, are optional — camera commands always " +
                    "work without them.",
        )

        SettingsGroup(
            title  = "Camera",
            footer = "Auto-fallback silently uses the phone camera if Meta glasses are " +
                     "unavailable or not connected.",
        ) {
            SettingsToggleRow(
                title           = "Vision enabled",
                description     = "Allow Jarvis to analyse images from the camera or screenshots.",
                checked         = visionEnabled,
                onCheckedChange = vm::setVisionEnabled,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Prefer phone camera",
                description     = "Use the phone camera for 'look at this' and camera commands " +
                                  "instead of Meta glasses when both are available.",
                checked         = preferPhoneCamera,
                onCheckedChange = vm::setPreferPhoneCamera,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Auto-fallback to phone camera",
                description     = "If glasses are selected but unavailable, fall back to the " +
                                  "phone camera automatically.",
                checked         = visionAutoFallback,
                onCheckedChange = vm::setVisionAutoFallbackToPhone,
            )
        }

        SettingsGroup(
            title  = "Screenshots",
            footer = "The screenshot listener watches your screenshot folder and automatically " +
                     "makes the latest screenshot available for follow-up commands like " +
                     "'read that' or 'what does that say'.",
        ) {
            SettingsToggleRow(
                title           = "Screenshot analysis",
                description     = "Analyse screenshots when you say 'look at my screen' or " +
                                  "'what's on my screen'.",
                checked         = screenshotAnalysis,
                onCheckedChange = vm::setScreenshotAnalysisEnabled,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Screenshot listener",
                description     = "Watch for new screenshots and load them into visual context " +
                                  "automatically (opt-in).",
                checked         = screenshotListener,
                onCheckedChange = vm::setScreenshotListenerEnabled,
            )
        }

        SettingsGroup(
            title  = "OCR",
            footer = "OCR lets Jarvis read text from images — labels, signs, error messages, " +
                     "documents — using the vision model.",
        ) {
            SettingsToggleRow(
                title           = "OCR enabled",
                description     = "Extract text from camera captures and screenshots.",
                checked         = ocrEnabled,
                onCheckedChange = vm::setOcrEnabled,
            )
        }

        SettingsGroup(
            title  = "Visual memory",
            footer = "Visual context is kept in memory for up to 10 minutes so follow-up " +
                     "commands work. Longer-term storage lets Jarvis reference recent images " +
                     "across sessions.",
        ) {
            SettingsToggleRow(
                title           = "Save visual context",
                description     = "Persist recent visual observations for use across sessions.",
                checked         = saveVisualContext,
                onCheckedChange = vm::setSaveVisualContext,
            )
            if (saveVisualContext) {
                SettingsRowDivider()
                SettingsSliderRow(
                    title        = "Retention",
                    description  = "How long to keep stored visual observations.",
                    value        = retentionDays.toFloat(),
                    valueRange   = 1f..90f,
                    steps        = 17,
                    valueLabel   = { v ->
                        val d = v.toInt()
                        if (d == 1) "1 day" else "$d days"
                    },
                    onValueChange = { vm.setVisualMemoryRetentionDays(it.toInt()) },
                )
            }
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Clear visual history",
                description = "Remove all stored visual observations from this device.",
                actionLabel = "Clear",
                destructive = true,
                confirm     = true,
                confirmCopy = "Clear",
                onAction    = { /* ToolRegistry / VisualContextStore.clear() wired at runtime */ },
            )
        }
    }
}
