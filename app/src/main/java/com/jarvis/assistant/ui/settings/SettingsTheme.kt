package com.jarvis.assistant.ui.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared palette, shapes and TextField styling for the settings surface.
 *
 * Kept separate from [com.jarvis.assistant.ui.SettingsScreen] so sub-screens
 * can share a single source of truth for look-and-feel.
 */
internal object SettingsTheme {
    val BgDark        = Color(0xFF0D0D0D)
    val Surface       = Color(0xFF1A1A2E)
    val SurfaceRaised = Color(0xFF20223A)
    val Divider       = Color(0xFF1A1A2E)
    val Border        = Color(0xFF2A2D48)
    val Cyan          = Color(0xFF00BCD4)
    val TextPrimary   = Color(0xFFE0E0E0)
    val TextMuted     = Color(0xFF888888)
    val TextFaint     = Color(0xFF666680)
    val Destructive   = Color(0xFFFF5252)
    val Success       = Color(0xFF00E676)
    val InfoBg        = Color(0xFF0D1B2A)
    val SuccessBg     = Color(0xFF0D2A1A)

    val GroupShape    = RoundedCornerShape(14.dp)
    val RowShape      = RoundedCornerShape(10.dp)
    val ChipShape     = RoundedCornerShape(6.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun settingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor      = SettingsTheme.Cyan,
    unfocusedBorderColor    = SettingsTheme.Border,
    focusedTextColor        = SettingsTheme.TextPrimary,
    unfocusedTextColor      = SettingsTheme.TextPrimary,
    cursorColor             = SettingsTheme.Cyan,
    focusedContainerColor   = SettingsTheme.SurfaceRaised,
    unfocusedContainerColor = SettingsTheme.SurfaceRaised,
)
