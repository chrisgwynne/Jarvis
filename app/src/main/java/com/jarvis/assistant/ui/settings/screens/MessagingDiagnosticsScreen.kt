package com.jarvis.assistant.ui.settings.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.notifications.JarvisNotificationListener
import com.jarvis.assistant.notifications.MessagingAppCapabilityRegistry
import com.jarvis.assistant.notifications.NotificationImportanceEngine
import com.jarvis.assistant.notifications.RecentMessageContext
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MessagingDiagnostics"

/**
 * MessagingDiagnosticsScreen — shows notification access state, recent
 * buffered notifications with their importance tier, current message context,
 * and reply-capable apps.
 */
@Composable
internal fun MessagingDiagnosticsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }

    val granted   = JarvisNotificationListener.isGranted(context)
    val connected = JarvisNotificationListener.isConnected()
    val recent    = remember(refresh) { JarvisNotificationListener.getRecent() }
    val messages  = remember(refresh) { JarvisNotificationListener.getRecentMessages() }
    val msgCtx    = remember(refresh) { RecentMessageContext.get() }

    SettingsScaffold(title = "Messaging Diagnostics", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Notification Intelligence",
            body  = "Jarvis reads notifications locally — no cloud, no OAuth. " +
                "Grant Notification Access so Jarvis can read and reply to messages.",
        )

        // ── Access status ──────────────────────────────────────────────────────
        SettingsGroup(title = "Notification Access") {
            StatusRow("Access granted",    granted)
            SettingsRowDivider()
            StatusRow("Listener connected", connected)
            SettingsRowDivider()
            StatusRow("Buffered total",     recent.size.toString())
            SettingsRowDivider()
            StatusRow("Buffered messages",  messages.size.toString())
        }

        if (!granted) {
            Spacer(Modifier.height(8.dp))
            SettingsGroup(title = "Setup required") {
                SettingsActionRow(
                    title       = "Open Notification Access",
                    description = "Tap to open the system settings panel",
                    actionLabel = "Open",
                    onAction    = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Active message context ─────────────────────────────────────────────
        SettingsGroup(
            title       = "Active message context",
            description = "Used for conversational 'reply yes' follow-ups",
        ) {
            if (msgCtx != null) {
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                CtxRow("Sender",  msgCtx.sender)
                SettingsRowDivider()
                CtxRow("App",     msgCtx.appName)
                SettingsRowDivider()
                CtxRow("Text",    msgCtx.text.take(80))
                SettingsRowDivider()
                CtxRow("At",      fmt.format(Date(msgCtx.timestampMs)))
                SettingsRowDivider()
                CtxRow("Can reply", msgCtx.replyableEntry?.canReply.toString())
            } else {
                Text(
                    text     = "No active context. Receive a message to populate.",
                    fontSize = 12.sp,
                    color    = SettingsTheme.TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Reply-capable apps ─────────────────────────────────────────────────
        val replyablePkgs = MessagingAppCapabilityRegistry.replyablePackages()
        val presentReplyable = recent
            .map { it.packageName }
            .distinct()
            .filter { it in replyablePkgs }

        SettingsGroup(
            title       = "Reply-capable apps in buffer",
            description = "${presentReplyable.size} app(s) with replyable notifications",
        ) {
            if (presentReplyable.isEmpty()) {
                Text(
                    text     = "No replyable notifications buffered.",
                    fontSize = 12.sp,
                    color    = SettingsTheme.TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                presentReplyable.forEachIndexed { i, pkg ->
                    val cap   = MessagingAppCapabilityRegistry.forPackage(pkg)
                    val count = recent.count { it.packageName == pkg && it.canReply }
                    Text(
                        text     = "${cap.displayName} — $count replyable",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    if (i < presentReplyable.lastIndex) SettingsRowDivider()
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Recent notifications ───────────────────────────────────────────────
        val toShow = recent.take(10)
        SettingsGroup(
            title       = "Last ${toShow.size} notifications",
            description = "Newest first — tap Refresh to update",
        ) {
            SettingsActionRow(
                title       = "Refresh buffer",
                description = "Reload from the live ring buffer",
                actionLabel = "Refresh",
                onAction    = { scope.launch { refresh++ } },
            )

            if (toShow.isEmpty()) {
                SettingsRowDivider()
                Text(
                    text     = "Buffer empty.",
                    fontSize = 12.sp,
                    color    = SettingsTheme.TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                toShow.forEachIndexed { i, entry ->
                    val imp    = NotificationImportanceEngine.classify(entry)
                    val impCol = when (imp) {
                        NotificationImportanceEngine.Importance.CRITICAL  -> SettingsTheme.Destructive
                        NotificationImportanceEngine.Importance.IMPORTANT -> SettingsTheme.Cyan
                        NotificationImportanceEngine.Importance.SPAM      -> SettingsTheme.TextMuted
                        else -> SettingsTheme.TextMuted
                    }
                    SettingsRowDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text       = entry.appName.ifBlank {
                                    MessagingAppCapabilityRegistry.displayName(entry.packageName)
                                },
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier   = Modifier.weight(1f),
                            )
                            Text(
                                text     = NotificationImportanceEngine.label(imp).uppercase(),
                                fontSize = 10.sp,
                                color    = impCol,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (entry.sender.isNotBlank()) {
                            Text(
                                text     = "From: ${entry.sender}",
                                fontSize = 12.sp,
                                color    = SettingsTheme.TextMuted,
                            )
                        }
                        Text(
                            text       = entry.text.take(100).ifBlank { entry.title },
                            fontSize   = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color      = SettingsTheme.TextMuted,
                            modifier   = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            text     = timeFmt.format(Date(entry.postedAt)) +
                                if (entry.canReply) " · can reply" else "",
                            fontSize = 10.sp,
                            color    = SettingsTheme.TextMuted,
                        )
                    }
                    if (i == toShow.lastIndex) Unit // no trailing divider needed
                }
            }
        }
    }
}

// ── Composable helpers ────────────────────────────────────────────────────────

@Composable
private fun StatusRow(label: String, value: Any) {
    val isOk = when (value) {
        is Boolean -> value
        is String  -> value != "false" && value != "0" && value.isNotBlank()
        else       -> true
    }
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            text       = value.toString(),
            fontSize   = 13.sp,
            color      = if (isOk) SettingsTheme.Cyan else SettingsTheme.Destructive,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CtxRow(label: String, value: String) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "$label:", fontSize = 12.sp, color = SettingsTheme.TextMuted, modifier = Modifier.weight(1f))
        Text(
            text       = value,
            fontSize   = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier   = Modifier.weight(2f),
        )
    }
}
