package com.jarvis.assistant.ui.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.speaker.db.PersonRecord
import com.jarvis.assistant.ui.SettingsViewModel
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTheme

@Composable
internal fun MemorySettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val profiles by vm.speakerProfiles.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadSpeakerProfiles() }

    SettingsScaffold(title = "Memory", onBack = onBack, onClose = onClose) {
        SettingsGroup(
            title = "Speaker profiles",
            footer = "Jarvis learns voices over time. Say your name when it asks \"Who's this?\" to start enrolling.",
        ) {
            if (profiles.isEmpty()) {
                SettingsInfoCard(
                    title = "No profiles yet",
                    body  = "As Jarvis meets people it will build a voice profile for each one.",
                )
            } else {
                profiles.forEachIndexed { index, person ->
                    SpeakerRow(person = person, onDelete = { vm.deleteSpeakerProfile(person.id) })
                    if (index < profiles.lastIndex) SettingsRowDivider()
                }
            }
        }

        SettingsGroup(
            title = "Stored data",
            footer = "Both actions are permanent and can't be undone.",
        ) {
            SettingsActionRow(
                title       = "Conversation history",
                description = "Raw dialogue logs — does not remove learned preferences or profile facts.",
                actionLabel = "Clear conversation history",
                destructive = true,
                confirm     = true,
                confirmCopy = "Clear history",
                onAction    = vm::clearConversationHistory,
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Learned memories",
                description = "Everything Jarvis remembers about you — preferences, facts and summaries.",
                actionLabel = "Clear all memories",
                destructive = true,
                confirm     = true,
                confirmCopy = "Clear memories",
                onAction    = vm::clearAllMemories,
            )
        }
    }
}

@Composable
private fun SpeakerRow(person: PersonRecord, onDelete: () -> Unit) {
    val statusLabel = when (person.typedEnrollmentStatus) {
        PersonRecord.EnrollmentStatus.NONE       -> "No voice samples"
        PersonRecord.EnrollmentStatus.TRAINING   -> "Training · ${person.enrolledUtteranceCount} samples"
        PersonRecord.EnrollmentStatus.SUFFICIENT -> "Sufficient · ${person.enrolledUtteranceCount} samples"
        PersonRecord.EnrollmentStatus.ENROLLED   -> "Enrolled · ${person.enrolledUtteranceCount} samples"
    }
    val statusColor = when (person.typedEnrollmentStatus) {
        PersonRecord.EnrollmentStatus.ENROLLED   -> SettingsTheme.Success
        PersonRecord.EnrollmentStatus.SUFFICIENT -> Color(0xFFFFD600)
        PersonRecord.EnrollmentStatus.TRAINING   -> Color(0xFFFF9800)
        PersonRecord.EnrollmentStatus.NONE       -> SettingsTheme.TextMuted
    }

    var confirming by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Person,
            contentDescription = null,
            tint = if (person.isOwner) SettingsTheme.Cyan else SettingsTheme.TextMuted,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(person.displayName, color = SettingsTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (person.isOwner) {
                    Text(
                        "Owner",
                        color = SettingsTheme.Cyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(SettingsTheme.Cyan.copy(alpha = 0.15f), SettingsTheme.ChipShape)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(statusLabel, color = statusColor, fontSize = 11.sp)
        }

        if (confirming) {
            TextButton(onClick = { confirming = false }) {
                Text("Cancel", color = SettingsTheme.TextMuted, fontSize = 12.sp)
            }
            TextButton(onClick = { onDelete(); confirming = false }) {
                Text("Delete", color = SettingsTheme.Destructive, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            IconButton(onClick = { confirming = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete ${person.displayName}",
                    tint = SettingsTheme.TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
