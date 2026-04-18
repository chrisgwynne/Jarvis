package com.jarvis.assistant.ui.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.SettingsViewModel
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTextFieldRow

@Composable
internal fun ActionsAppsSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val braveKey by vm.braveSearchApiKey.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Actions & Apps", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Built-in actions",
            body  = "Contacts, phone calls and SMS all work automatically once you " +
                    "grant Jarvis the relevant permissions — no configuration needed.",
        )

        SettingsGroup(
            title  = "Web search",
            footer = "DuckDuckGo is used by default (free, no key). Add a Brave key for " +
                     "richer results — get one free at search.brave.com/settings.",
        ) {
            SettingsTextFieldRow(
                title        = "Brave Search API key",
                description  = "Optional. Leave blank to fall back to DuckDuckGo.",
                value        = braveKey,
                onValueChange = vm::setBraveSearchApiKey,
                placeholder  = "BSA…",
                isSecret     = true,
            )
        }
    }
}
