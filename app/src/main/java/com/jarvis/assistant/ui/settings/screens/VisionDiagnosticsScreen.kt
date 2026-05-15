package com.jarvis.assistant.ui.settings.screens

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsValueRow
import com.jarvis.assistant.wearables.meta.MetaWearablesState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun VisionDiagnosticsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context      = LocalContext.current
    val store        = JarvisApp.visualContextStore
    val ctx          by store.contextFlow.collectAsState()
    val metaState    by JarvisApp.metaWearables.stateFlow.collectAsState(
        initial = JarvisApp.metaWearables.currentState
    )

    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    SettingsScaffold(title = "Vision Diagnostics", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Live visual context",
            body  = "Shows the most recent image Jarvis has in memory. Context expires " +
                    "after 10 minutes. Trigger a vision command ('look at this', 'read this') " +
                    "to populate it.",
        )

        SettingsGroup(title = "Current context") {
            SettingsValueRow(
                title = "Source",
                value = ctx?.source?.name?.lowercase()?.replace('_', ' ') ?: "none",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Captured at",
                value = ctx?.capturedAtMs?.let { timeFmt.format(Date(it)) } ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Expired",
                value = ctx?.let { if (it.isExpired()) "Yes" else "No" } ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "App",
                value = ctx?.appName ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "Summary",
                value       = ctx?.summary
                    ?.take(80)?.let { if (it.length == 80) "$it…" else it } ?: "—",
                description = "First 80 chars of the vision summary",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "OCR text",
                value       = ctx?.ocrText
                    ?.take(80)?.let { if (it.length == 80) "$it…" else it } ?: "—",
                description = "First 80 chars of extracted text",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Image file",
                value = ctx?.imageFilePath?.substringAfterLast('/') ?: "—",
            )
        }

        SettingsGroup(title = "Capture source") {
            val preferred = if (JarvisApp.wearablesSettings.snapshot().enabled &&
                                metaState == MetaWearablesState.CAMERA_READY) {
                "Meta glasses"
            } else {
                "Phone camera"
            }
            SettingsValueRow(
                title = "Active source",
                value = preferred,
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Meta glasses state",
                value = metaState.name.lowercase().replace('_', ' '),
            )
            SettingsRowDivider()
            val fallbackActive = metaState != MetaWearablesState.CAMERA_READY &&
                                 metaState != MetaWearablesState.STREAMING
            SettingsValueRow(
                title = "Phone camera fallback",
                value = if (fallbackActive) "Active" else "Not needed",
            )
        }

        SettingsGroup(
            title  = "Actions",
            footer = "Use voice commands to test each path: say 'look at this' for " +
                     "screenshots, 'take a photo' for camera, or 'read this' for OCR.",
        ) {
            SettingsActionRow(
                title       = "Clear visual context",
                description = "Remove the current image from memory.",
                actionLabel = "Clear",
                destructive = true,
                onAction    = {
                    store.clear()
                    Toast.makeText(context, "Visual context cleared", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
