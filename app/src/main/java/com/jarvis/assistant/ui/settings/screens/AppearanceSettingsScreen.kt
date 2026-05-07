package com.jarvis.assistant.ui.settings.screens

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ui.SettingsViewModel
import com.jarvis.assistant.ui.settings.SettingsDropdownRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsToggleRow

/**
 * Appearance settings — theme mode + dynamic colour toggle.
 *
 * Both controls write through [SettingsViewModel] and trigger an
 * [Activity.recreate] so the new colour scheme takes effect immediately.
 * The recreate is deferred via the SettingsStore write so the prefs change
 * lands on disk before the host Activity rebuilds its composition.
 */
@Composable
internal fun AppearanceSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by vm.dynamicColor.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    SettingsScaffold(title = "Appearance", onBack = onBack, onClose = onClose) {

        SettingsGroup(title = "Theme") {
            SettingsDropdownRow(
                title       = "Theme mode",
                description = "AMOLED is true black — best for OLED battery on this always-on app.",
                options     = listOf("system", "light", "dark", "amoled"),
                selected    = themeMode,
                label       = ::themeLabel,
                onSelected  = { selected ->
                    vm.setThemeMode(selected)
                    activity?.recreate()
                },
            )

            // Dynamic colour is Android 12+ only and incompatible with AMOLED
            // (true-black is the whole point of that mode).
            val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val dynamicEnabled   = dynamicSupported && themeMode != "amoled"
            SettingsToggleRow(
                title       = "Dynamic colour",
                description = when {
                    !dynamicSupported     -> "Requires Android 12 or newer."
                    themeMode == "amoled" -> "Disabled while AMOLED is active."
                    else                  -> "Tint Jarvis with colours derived from your wallpaper."
                },
                checked         = dynamicColor && dynamicEnabled,
                enabled         = dynamicEnabled,
                onCheckedChange = { v ->
                    vm.setDynamicColor(v)
                    activity?.recreate()
                },
            )
        }

        SettingsInfoCard(
            title = "Battery saving",
            body  = "AMOLED mode keeps the screen physically dark on OLED panels. " +
                    "On always-on workloads like Jarvis that's a measurable battery win.",
        )
    }
}

private fun themeLabel(mode: String): String = when (mode) {
    "system" -> "Match system"
    "light"  -> "Light"
    "dark"   -> "Dark"
    "amoled" -> "AMOLED black"
    else     -> mode
}
