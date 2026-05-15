package com.jarvis.assistant.ui.settings.screens

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsValueRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AppControlDiagnosticsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context     = LocalContext.current
    val recentStore = JarvisApp.recentAppContextStore
    val navStore    = JarvisApp.mapsNavigationContextStore
    val recentCtx   by recentStore.contextFlow.collectAsState()
    val navCtx      by navStore.contextFlow.collectAsState()

    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    SettingsScaffold(title = "App Control Diagnostics", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Live state",
            body  = "Shows the current app context and Maps navigation state Jarvis is " +
                    "tracking. Open or close an app to populate recent context; say " +
                    "'navigate to X' to populate the Maps context.",
        )

        SettingsGroup(title = "Recent app context") {
            val rc = recentStore.current
            SettingsValueRow(
                title = "App name",
                value = rc?.appName ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Package",
                value = rc?.packageName ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Opened at",
                value = rc?.openedAtMs?.let { timeFmt.format(Date(it)) } ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Expired",
                value = recentCtx?.let { if (it.isExpired()) "Yes" else "No" } ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Last action",
                value = rc?.lastAction ?: "—",
            )
        }

        SettingsGroup(title = "Maps navigation context") {
            val nc = navStore.current
            SettingsValueRow(
                title = "Destination",
                value = nc?.destination ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Mode",
                value = nc?.mode?.name?.lowercase() ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Route loaded at",
                value = nc?.routeLoadedAt?.let { timeFmt.format(Date(it)) } ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Expired",
                value = navCtx?.let { if (it.isExpired()) "Yes" else "No" } ?: "—",
            )
        }

        SettingsGroup(title = "Accessibility") {
            val connected = JarvisAccessibilityService.isConnected()
            SettingsValueRow(
                title = "Accessibility Service",
                value = if (connected) "Connected" else "Not connected",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "Back action",
                value       = if (connected) "Global action (BACK)" else "Not available",
                description = "How 'go back' is executed",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "Home action",
                value       = if (connected) "Global action (HOME)" else "HOME intent",
                description = "How 'go home' / 'close app' is executed",
            )
        }

        SettingsGroup(
            title  = "Actions",
            footer = "These clear only the in-memory context; they do not affect any " +
                     "currently open apps.",
        ) {
            SettingsActionRow(
                title       = "Clear recent app context",
                description = "Remove the current 'last opened app' record.",
                actionLabel = "Clear",
                destructive = true,
                onAction    = {
                    recentStore.clear()
                    Toast.makeText(context, "Recent app context cleared", Toast.LENGTH_SHORT).show()
                },
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Clear Maps navigation context",
                description = "Remove the active navigation destination and mode.",
                actionLabel = "Clear",
                destructive = true,
                onAction    = {
                    navStore.clear()
                    Toast.makeText(context, "Maps navigation context cleared", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
