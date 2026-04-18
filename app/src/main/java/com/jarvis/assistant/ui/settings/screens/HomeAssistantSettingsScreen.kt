package com.jarvis.assistant.ui.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.SettingsViewModel
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsPrimaryButton
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTextFieldRow
import com.jarvis.assistant.ui.settings.SettingsTheme

@Composable
internal fun HomeAssistantSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val haBaseUrl  by vm.haBaseUrl.collectAsStateWithLifecycle()
    val haApiToken by vm.haApiToken.collectAsStateWithLifecycle()
    val haStatus   by vm.haConnectionStatus.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Home Assistant", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Smart home control",
            body  = "Connect Jarvis to your local Home Assistant instance to control " +
                    "lights, switches, locks, climate, covers, scenes and scripts by voice.",
        )

        SettingsGroup(
            title  = "Connection",
            footer = "Create a long-lived access token from your Home Assistant profile page " +
                     "(bottom of the page → \"Long-Lived Access Tokens\").",
        ) {
            SettingsTextFieldRow(
                title         = "Base URL",
                description   = "Your Home Assistant server address.",
                value         = haBaseUrl,
                onValueChange = vm::setHaBaseUrl,
                placeholder   = "http://homeassistant.local:8123",
            )
            SettingsRowDivider()
            SettingsTextFieldRow(
                title         = "Long-lived access token",
                value         = haApiToken,
                onValueChange = vm::setHaApiToken,
                placeholder   = "eyJhbGciOiJIUzI1NiIs…",
                isSecret      = true,
            )
            SettingsRowDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Connection status",
                        color = SettingsTheme.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = haStatus ?: "Not tested",
                        color = haStatusColor(haStatus),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                SettingsPrimaryButton(
                    label   = if (haStatus == "Connecting…") "Testing…" else "Test connection",
                    enabled = haStatus != "Connecting…",
                    onClick = vm::testHaConnection,
                )
            }
        }

        SettingsInfoCard(
            title = "Example commands",
            body  = "\"Turn on the kitchen lights\" · \"Set the living room to 21\" · " +
                    "\"Run bedtime scene\" · \"Open the garage\" · " +
                    "\"What's the temperature in the bedroom?\"",
        )
    }
}

private fun haStatusColor(status: String?) = when {
    status == null                          -> SettingsTheme.TextMuted
    status == "Connecting…"                 -> SettingsTheme.Cyan
    status.startsWith("Connected")          -> SettingsTheme.Success
    else                                    -> SettingsTheme.Destructive
}
