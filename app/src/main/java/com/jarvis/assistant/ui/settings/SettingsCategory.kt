package com.jarvis.assistant.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.EmojiPeople
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every first-level section in Settings. Adding a new category is a three-step
 * change:
 *
 *   1. Add an entry here (title, description, icon, route).
 *   2. Add a composable() case for [route] in [SettingsScreen]'s NavHost.
 *   3. Create the actual screen Composable under
 *      `com.jarvis.assistant.ui.settings.screens`.
 */
internal enum class SettingsCategory(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val route: String,
) {
    Conversation(
        title       = "Conversation",
        description = "How Jarvis replies and routes messages",
        icon        = Icons.Filled.ChatBubbleOutline,
        route       = "settings/conversation",
    ),
    Voice(
        title       = "Voice",
        description = "Wake word, spoken replies and TTS voice",
        icon        = Icons.Filled.RecordVoiceOver,
        route       = "settings/voice",
    ),
    Memory(
        title       = "Memory",
        description = "Speaker profiles and stored history",
        icon        = Icons.Filled.Memory,
        route       = "settings/memory",
    ),
    Proactivity(
        title       = "Proactivity",
        description = "When Jarvis speaks up on its own",
        icon        = Icons.Filled.Bolt,
        route       = "settings/proactivity",
    ),
    Personality(
        title       = "Personality",
        description = "Sarcasm, humour, pushback, serious-mode auto-detect",
        icon        = Icons.Filled.EmojiPeople,
        route       = "settings/personality",
    ),
    Todoist(
        title       = "Todoist",
        description = "Reminders, tasks and offline sync",
        icon        = Icons.Filled.CheckCircle,
        route       = "settings/todoist",
    ),
    LocalDiagnostics(
        title       = "Diagnostics",
        description = "Recent local routes and remote-touched audit",
        icon        = Icons.Filled.Bolt,
        route       = "settings/diagnostics",
    ),
    ActionsApps(
        title       = "Actions & Apps",
        description = "Tools, search and connected apps",
        icon        = Icons.Filled.Shield,
        route       = "settings/actions",
    ),
    HomeAssistant(
        title       = "Home Assistant",
        description = "Connect Jarvis to your smart home",
        icon        = Icons.Filled.Home,
        route       = "settings/homeassistant",
    ),
    Notifications(
        title       = "Notifications",
        description = "Alerts and status messages",
        icon        = Icons.Filled.NotificationsNone,
        route       = "settings/notifications",
    ),
    Privacy(
        title       = "Privacy",
        description = "Data, accounts and encryption",
        icon        = Icons.Filled.Security,
        route       = "settings/privacy",
    ),
    Appearance(
        title       = "Appearance",
        description = "Theme and display density",
        icon        = Icons.Filled.Palette,
        route       = "settings/appearance",
    ),
    Advanced(
        title       = "Advanced",
        description = "LLM provider, keys and remote backend",
        icon        = Icons.Filled.Tune,
        route       = "settings/advanced",
    ),
    ExperimentalFlags(
        title       = "Experimental Features",
        description = "Toggle experimental Jarvis subsystems",
        icon        = Icons.Filled.Science,
        route       = "settings/experimental",
    ),
    Faq(
        title       = "FAQ & Commands",
        description = "What Jarvis can do and how to ask",
        icon        = Icons.AutoMirrored.Filled.HelpOutline,
        route       = "settings/faq",
    ),
}

internal const val SETTINGS_ROOT_ROUTE = "settings/root"
