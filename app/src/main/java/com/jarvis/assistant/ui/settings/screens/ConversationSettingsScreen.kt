package com.jarvis.assistant.ui.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.SettingsViewModel
import com.jarvis.assistant.ui.settings.SettingsDropdownRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsScaffold

private data class MsgChannel(val key: String, val label: String)

private val CHANNELS = listOf(
    MsgChannel("ask",      "Ask each time"),
    MsgChannel("sms",      "SMS"),
    MsgChannel("whatsapp", "WhatsApp"),
)

@Composable
internal fun ConversationSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val defaultChannel by vm.defaultMsgChannel.collectAsStateWithLifecycle()
    val selected = CHANNELS.firstOrNull { it.key == defaultChannel } ?: CHANNELS.first()

    SettingsScaffold(
        title = "Conversation",
        onBack = onBack,
        onClose = onClose,
    ) {
        SettingsGroup(
            title = "Messaging",
            footer = "Controls the default channel Jarvis uses when you ask it to send a message.",
        ) {
            SettingsDropdownRow(
                title       = "Default channel",
                description = "Used when you don't specify how to send a message.",
                options     = CHANNELS,
                selected    = selected,
                label       = { it.label },
                onSelected  = { vm.setDefaultMsgChannel(it.key) },
            )
        }

        SettingsInfoCard(
            title = "Tip",
            body  = "You can still override the channel per-message by saying " +
                    "\"text them on WhatsApp\" or \"send it as an SMS\".",
        )
    }
}
