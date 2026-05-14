package com.jarvis.assistant.ui.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.diagnostics.LocalRouteDiagnostics
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTheme
import java.text.DateFormat
import java.util.Date

/**
 * LocalDiagnosticsScreen — surfaces the in-memory ring buffer from
 * [LocalRouteDiagnostics] so the user can audit recent local-tool
 * dispatches.  Each row shows transcript / normalised transcript /
 * intent / slots / route / result / latency / whether remote
 * subsystems were touched.
 *
 * Phone-capable intents should always show route=LOCAL_ONLY and
 * remoteTouched=false; anything else is flagged in destructive red.
 */
@Composable
internal fun LocalDiagnosticsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val entries by LocalRouteDiagnostics.stateFlow.collectAsState()

    SettingsScaffold(title = "Diagnostics", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Local routing audit",
            body  = "Phone-capable commands should resolve LOCAL_ONLY with no " +
                "remote subsystems touched.  Anything tagged remote here " +
                "indicates a routing regression — those entries are also " +
                "auto-filed as GitHub issues.",
        )

        SettingsGroup(
            title       = "Recent routes",
            description = "Last 30 local dispatches, newest first",
        ) {
            SettingsActionRow(
                title       = "Clear log",
                description = "Discard the in-memory buffer (process-local, no persistence).",
                actionLabel = "Clear",
                destructive = true,
                confirm     = true,
                onAction    = { LocalRouteDiagnostics.clear() },
            )
            SettingsRowDivider()
            if (entries.isEmpty()) {
                Text(
                    "No local routes recorded yet.",
                    color    = SettingsTheme.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    entries.forEachIndexed { idx, e ->
                        RouteRow(e)
                        if (idx != entries.lastIndex) SettingsRowDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteRow(e: LocalRouteDiagnostics.Entry) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${e.intent} · ${e.tool}",
                fontWeight = FontWeight.Medium,
                fontSize   = 13.sp,
            )
            Text(
                "${e.latencyMs} ms",
                fontSize = 12.sp,
                color    = if (e.latencyMs <= 1000) SettingsTheme.Success
                           else                       SettingsTheme.TextMuted,
            )
        }
        Text(
            "“${e.transcript}”",
            fontSize = 12.sp,
            color    = SettingsTheme.TextPrimary,
        )
        if (e.normalisedTranscript != e.transcript &&
            e.normalisedTranscript.isNotBlank()
        ) {
            Text(
                "normalised: ${e.normalisedTranscript}",
                fontSize = 11.sp,
                color    = SettingsTheme.TextMuted,
            )
        }
        if (e.slots.isNotEmpty()) {
            Text(
                "slots: ${e.slots.entries.joinToString { "${it.key}=${it.value}" }}",
                fontSize = 11.sp,
                color    = SettingsTheme.TextMuted,
                fontFamily = FontFamily.Monospace,
            )
        }
        val time = DateFormat.getTimeInstance(DateFormat.MEDIUM)
            .format(Date(e.timestampMs))
        val routeColor = if (e.remoteTouched) SettingsTheme.Destructive
                         else                  SettingsTheme.Cyan
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "$time · ${e.route} · ${e.result}",
                fontSize = 11.sp,
                color    = routeColor,
            )
            if (e.remoteTouched) {
                Text("REMOTE-TOUCHED", fontSize = 11.sp,
                    color = SettingsTheme.Destructive, fontWeight = FontWeight.Bold)
            }
        }
    }
}
