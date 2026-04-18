package com.jarvis.assistant.ui.settings

import androidx.compose.runtime.Composable

/**
 * Root "Settings" screen — a clean list of categories and nothing else.
 *
 * Deliberately does NOT render actual settings; individual options live on
 * the dedicated sub-screens so this surface stays easy to scan.
 */
@Composable
internal fun SettingsRootScreen(
    onOpenCategory: (SettingsCategory) -> Unit,
    onClose: () -> Unit,
) {
    SettingsScaffold(
        title = "Settings",
        onBack = null,
        onClose = onClose,
        showHeadline = false,
    ) {
        SettingsGroup {
            val categories = SettingsCategory.entries
            categories.forEachIndexed { index, category ->
                SettingsCategoryRow(
                    icon        = category.icon,
                    title       = category.title,
                    description = category.description,
                    onClick     = { onOpenCategory(category) },
                )
                if (index < categories.lastIndex) SettingsRowDivider()
            }
        }
    }
}
