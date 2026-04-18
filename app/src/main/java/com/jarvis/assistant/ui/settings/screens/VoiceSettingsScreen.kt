package com.jarvis.assistant.ui.settings.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.SettingsViewModel
import com.jarvis.assistant.ui.TtsVoiceInfo
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTextFieldRow
import com.jarvis.assistant.ui.settings.SettingsTheme
import com.jarvis.assistant.ui.settings.SettingsToggleRow
import com.jarvis.assistant.ui.settings.SettingsValueRow

@Composable
internal fun VoiceSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    val wakeWord        by vm.wakeWord.collectAsStateWithLifecycle()
    val voiceResponse   by vm.voiceResponse.collectAsStateWithLifecycle()
    val ttsVoiceName    by vm.ttsVoiceName.collectAsStateWithLifecycle()
    val availableVoices by vm.availableVoices.collectAsStateWithLifecycle()

    var voicePickerExpanded by remember { mutableStateOf(false) }

    SettingsScaffold(title = "Voice", onBack = onBack, onClose = onClose) {
        SettingsGroup(title = "Wake word") {
            SettingsTextFieldRow(
                title        = "Phrase",
                description  = "Say this phrase to wake Jarvis.",
                value        = wakeWord,
                onValueChange = vm::setWakeWord,
                placeholder  = "Jarvis",
            )
        }

        SettingsGroup(title = "Speech output") {
            SettingsToggleRow(
                title       = "Speak replies aloud",
                description = "Use text-to-speech for responses.",
                checked     = voiceResponse,
                onCheckedChange = vm::setVoiceResponse,
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "Voice",
                description = "Tap to choose a voice; hear a preview on selection.",
                value       = selectedVoiceLabel(ttsVoiceName, availableVoices),
                onClick     = {
                    if (availableVoices.isNotEmpty()) voicePickerExpanded = true
                },
            )
        }

        // Inline voice picker (modal-ish list expanded inside the page)
        if (voicePickerExpanded) {
            VoicePickerPanel(
                selectedKey    = ttsVoiceName,
                voices         = availableVoices,
                onPick         = { key ->
                    vm.setTtsVoiceName(key)
                    if (key.isNotBlank()) vm.previewVoice(key)
                    voicePickerExpanded = false
                },
                onDismiss      = { voicePickerExpanded = false },
            )
        }

        TextButton(onClick = {
            try {
                context.startActivity(
                    Intent("com.android.settings.TTS_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }) {
            Text(
                "+ Install more voices from Android settings",
                color = SettingsTheme.Cyan,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun VoicePickerPanel(
    selectedKey: String,
    voices: List<TtsVoiceInfo>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val offline = voices.filter { !it.isNetwork }
    val online  = voices.filter { it.isNetwork }

    SettingsGroup(title = "Choose voice", footer = "Tap a voice to select it and play a preview.") {
        VoiceRow(label = "System default", isSelected = selectedKey.isBlank(), onClick = { onPick("") })

        if (offline.isNotEmpty()) {
            SettingsRowDivider()
            SectionLabelRow("On-device")
            offline.forEach { v ->
                SettingsRowDivider()
                VoiceRow(
                    label      = v.displayName,
                    isSelected = v.name == selectedKey,
                    onClick    = { onPick(v.name) },
                )
            }
        }
        if (online.isNotEmpty()) {
            SettingsRowDivider()
            SectionLabelRow("Online")
            online.forEach { v ->
                SettingsRowDivider()
                VoiceRow(
                    label      = v.displayName,
                    isSelected = v.name == selectedKey,
                    onClick    = { onPick(v.name) },
                )
            }
        }
        SettingsRowDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text("Close", color = SettingsTheme.TextMuted)
            }
        }
    }
}

@Composable
private fun SectionLabelRow(label: String) {
    Text(
        text = label.uppercase(),
        color = SettingsTheme.TextFaint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun VoiceRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (isSelected) SettingsTheme.Cyan else SettingsTheme.TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Preview",
            tint = SettingsTheme.Cyan,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun selectedVoiceLabel(key: String, voices: List<TtsVoiceInfo>): String = when {
    key.isBlank() -> "System default"
    else          -> voices.find { it.name == key }?.displayName ?: key
}
