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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SessionDiagnosticsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context     = LocalContext.current
    val engine      = JarvisApp.sessionStateEngine
    val session     by engine.sessionFlow.collectAsState()

    val haCtxStore  = JarvisApp.recentHaContextStore
    val msgCtxStore = JarvisApp.recentMessageContextStore
    val calCtxStore = JarvisApp.recentCalendarContextStore
    val haCtx       by haCtxStore.contextFlow.collectAsState()
    val msgCtx      by msgCtxStore.contextFlow.collectAsState()
    val calCtx      by calCtxStore.contextFlow.collectAsState()

    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    SettingsScaffold(title = "Session Diagnostics", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Live session state",
            body  = "Shows the current session, active goal, pending slots and context " +
                    "stores in real time. State updates as you speak to Jarvis.",
        )

        // ── Session ────────────────────────────────────────────────────────────
        SettingsGroup(title = "Session") {
            SettingsValueRow(
                title = "Session ID",
                value = session?.sessionId?.take(8)?.plus("…") ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Listening state",
                value = session?.listeningState?.name?.lowercase()?.replace('_', ' ') ?: "idle",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Started at",
                value = session?.startedAt?.let { timeFmt.format(Date(it)) } ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Expires at",
                value = session?.expiresAt?.let { timeFmt.format(Date(it)) } ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Last user speech",
                value = session?.lastUserSpeechAt?.let { timeFmt.format(Date(it)) } ?: "—",
            )
        }

        // ── Active goal ────────────────────────────────────────────────────────
        SettingsGroup(title = "Active goal") {
            val goal = session?.activeGoal
            SettingsValueRow(
                title = "Type",
                value = goal?.type?.name?.lowercase()?.replace('_', ' ') ?: "none",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Status",
                value = goal?.status?.name?.lowercase() ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Confidence",
                value = goal?.confidence?.let { "%.0f%%".format(it * 100) } ?: "—",
            )
            SettingsRowDivider()
            val slots = goal?.slots?.joinToString(", ") { s ->
                "${s.name}=${s.filled ?: "pending"}"
            }
            SettingsValueRow(
                title = "Slots",
                value = slots?.ifBlank { "none" } ?: "—",
            )
            SettingsRowDivider()
            val next = goal?.nextUnfilledSlot
            SettingsValueRow(
                title       = "Next unfilled slot",
                value       = next?.name ?: "—",
                description = next?.prompt,
            )
        }

        // ── Pending action ─────────────────────────────────────────────────────
        SettingsGroup(title = "Pending action") {
            val pa = session?.pendingAction
            SettingsValueRow(
                title = "Tool",
                value = pa?.toolName ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Pending slot",
                value = pa?.pendingSlot ?: "—",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title = "Prompt",
                value = pa?.prompt ?: "—",
            )
        }

        // ── Context stores ─────────────────────────────────────────────────────
        SettingsGroup(title = "Home Assistant context") {
            val ha = haCtxStore.current
            SettingsValueRow(title = "Entity",     value = ha?.friendlyName ?: "—")
            SettingsRowDivider()
            SettingsValueRow(title = "Domain",     value = ha?.domain ?: "—")
            SettingsRowDivider()
            SettingsValueRow(title = "Last action",value = ha?.lastAction ?: "—")
            SettingsRowDivider()
            SettingsValueRow(
                title = "Expires",
                value = haCtx?.recordedAt?.let {
                    val expiresAt = it + com.jarvis.assistant.session.context
                        .RecentHomeAssistantContext.EXPIRY_MS
                    timeFmt.format(Date(expiresAt))
                } ?: "—"
            )
        }

        SettingsGroup(title = "Message context") {
            val msg = msgCtxStore.current
            SettingsValueRow(title = "Sender",  value = msg?.sender ?: "—")
            SettingsRowDivider()
            SettingsValueRow(title = "Channel", value = msg?.channel?.name?.lowercase() ?: "—")
            SettingsRowDivider()
            SettingsValueRow(
                title = "Body",
                value = msg?.body?.take(60)?.let { if (it.length == 60) "$it…" else it } ?: "—",
            )
        }

        SettingsGroup(title = "Calendar context") {
            val cal = calCtxStore.current
            SettingsValueRow(title = "Event",    value = cal?.title ?: "—")
            SettingsRowDivider()
            SettingsValueRow(
                title = "Start",
                value = cal?.startMs?.let { timeFmt.format(Date(it)) } ?: "—",
            )
        }

        // ── Actions ────────────────────────────────────────────────────────────
        SettingsGroup(
            title  = "Actions",
            footer = "These clear in-memory state only; they do not cancel any " +
                     "in-progress actions.",
        ) {
            SettingsActionRow(
                title       = "Cancel active goal",
                description = "Clears the active goal and any pending slots.",
                actionLabel = "Cancel",
                destructive = true,
                onAction    = {
                    engine.cancelAll()
                    Toast.makeText(context, "Session goal cancelled", Toast.LENGTH_SHORT).show()
                },
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Clear HA context",
                description = "Remove the tracked Home Assistant entity.",
                actionLabel = "Clear",
                destructive = true,
                onAction    = {
                    haCtxStore.clear()
                    Toast.makeText(context, "HA context cleared", Toast.LENGTH_SHORT).show()
                },
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Clear message context",
                description = "Remove the tracked message sender.",
                actionLabel = "Clear",
                destructive = true,
                onAction    = {
                    msgCtxStore.clear()
                    Toast.makeText(context, "Message context cleared", Toast.LENGTH_SHORT).show()
                },
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Clear calendar context",
                description = "Remove the tracked calendar event.",
                actionLabel = "Clear",
                destructive = true,
                onAction    = {
                    calCtxStore.clear()
                    Toast.makeText(context, "Calendar context cleared", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}
