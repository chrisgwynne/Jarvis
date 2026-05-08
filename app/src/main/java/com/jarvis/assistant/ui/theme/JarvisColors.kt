package com.jarvis.assistant.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * JarvisColors — the brand palette and three baseline schemes.
 *
 * The brand identity is the cyan accent on a near-black or true-black surface.
 * Three variants are exposed:
 *
 *   • Dark   — the existing look.  Surface is a slightly-lifted near-black
 *              (#12121E) so the depth between background, surface and
 *              surface-raised reads on OLED and LCD panels alike.
 *   • AMOLED — true black background.  On OLED the dark pixels are physically
 *              off, which materially reduces battery for an always-on app
 *              like Jarvis.
 *   • Light  — high-contrast light scheme.  Mostly for accessibility / users
 *              who actively prefer light surfaces; the cyan accent is darkened
 *              so it stays readable on white.
 *
 * Material 3's dynamic-color builder is applied at the JarvisTheme level
 * when the user opts in (Android 12+).  These three schemes are the fallback
 * and the explicit user choice for "ignore wallpaper colours."
 */
object JarvisBrand {
    val cyan      = Color(0xFF00BCD4)
    val cyanDeep  = Color(0xFF0097A7)
    val green     = Color(0xFF00E676)
    val amber     = Color(0xFFFFAB40)
    val purple    = Color(0xFFCE93D8)
    val red       = Color(0xFFFF5252)
}

/**
 * Extra colours that don't fit neatly into the M3 ColorScheme contract
 * (status indicators, raised surfaces, divider tints).  Exposed via
 * [LocalJarvisExtraColors] so screens can read them off the composition.
 */
@Immutable
data class JarvisExtraColors(
    val surfaceRaised: Color,
    val divider: Color,
    val border: Color,
    val textMuted: Color,
    val textFaint: Color,
    val statusGreen: Color,
    val statusAmber: Color,
    val statusPurple: Color,
    val statusRed: Color,
    val infoBg: Color,
    val successBg: Color,
)

val LocalJarvisExtraColors = staticCompositionLocalOf {
    // Sensible default — the dark palette.  Real values come from JarvisTheme.
    DarkExtras
}

// ── Dark scheme ──────────────────────────────────────────────────────────────

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary           = JarvisBrand.cyan,
    onPrimary         = Color(0xFF00131A),
    primaryContainer  = Color(0xFF003D49),
    onPrimaryContainer = Color(0xFF6FF6FF),
    secondary         = JarvisBrand.purple,
    onSecondary       = Color(0xFF1A1024),
    background        = Color(0xFF08080F),
    onBackground      = Color(0xFFDDDDEE),
    surface           = Color(0xFF12121E),
    onSurface         = Color(0xFFE0E0E0),
    surfaceVariant    = Color(0xFF1A1A2E),
    onSurfaceVariant  = Color(0xFFB0B0C0),
    error             = JarvisBrand.red,
    onError           = Color(0xFF1A0508),
    outline           = Color(0xFF2A2D48),
)

internal val DarkExtras = JarvisExtraColors(
    surfaceRaised = Color(0xFF20223A),
    divider       = Color(0xFF1A1A2E),
    border        = Color(0xFF2A2D48),
    textMuted     = Color(0xFF505065),
    textFaint     = Color(0xFF383850),
    statusGreen   = JarvisBrand.green,
    statusAmber   = JarvisBrand.amber,
    statusPurple  = JarvisBrand.purple,
    statusRed     = JarvisBrand.red,
    infoBg        = Color(0xFF0D1B2A),
    successBg     = Color(0xFF0D2A1A),
)

// ── AMOLED — true black for OLED battery savings on an always-on app ─────────

internal val AmoledColorScheme: ColorScheme = darkColorScheme(
    primary           = JarvisBrand.cyan,
    onPrimary         = Color(0xFF00131A),
    primaryContainer  = Color(0xFF002830),
    onPrimaryContainer = Color(0xFF6FF6FF),
    secondary         = JarvisBrand.purple,
    onSecondary       = Color(0xFF1A1024),
    background        = Color.Black,
    onBackground      = Color(0xFFDDDDEE),
    surface           = Color.Black,
    onSurface         = Color(0xFFE0E0E0),
    surfaceVariant    = Color(0xFF0A0A14),
    onSurfaceVariant  = Color(0xFFB0B0C0),
    error             = JarvisBrand.red,
    onError           = Color(0xFF1A0508),
    outline           = Color(0xFF1F2030),
)

internal val AmoledExtras = JarvisExtraColors(
    surfaceRaised = Color(0xFF0F0F18),
    divider       = Color(0xFF0A0A14),
    border        = Color(0xFF1F2030),
    textMuted     = Color(0xFF505065),
    textFaint     = Color(0xFF383850),
    statusGreen   = JarvisBrand.green,
    statusAmber   = JarvisBrand.amber,
    statusPurple  = JarvisBrand.purple,
    statusRed     = JarvisBrand.red,
    infoBg        = Color(0xFF050D15),
    successBg     = Color(0xFF05140C),
)

// ── Light ────────────────────────────────────────────────────────────────────

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary           = JarvisBrand.cyanDeep,
    onPrimary         = Color.White,
    primaryContainer  = Color(0xFFB2EBF2),
    onPrimaryContainer = Color(0xFF002830),
    secondary         = Color(0xFF7B1FA2),
    onSecondary       = Color.White,
    background        = Color(0xFFF8F9FB),
    onBackground      = Color(0xFF1A1A24),
    surface           = Color.White,
    onSurface         = Color(0xFF1A1A24),
    surfaceVariant    = Color(0xFFEEF0F4),
    onSurfaceVariant  = Color(0xFF454556),
    error             = Color(0xFFC62828),
    onError           = Color.White,
    outline           = Color(0xFFC4C7D2),
)

internal val LightExtras = JarvisExtraColors(
    surfaceRaised = Color(0xFFFFFFFF),
    divider       = Color(0xFFE4E6EC),
    border        = Color(0xFFC4C7D2),
    textMuted     = Color(0xFF6B6B80),
    textFaint     = Color(0xFF9A9AB0),
    statusGreen   = Color(0xFF1B7F3D),
    statusAmber   = Color(0xFFB85C00),
    statusPurple  = Color(0xFF7B1FA2),
    statusRed     = Color(0xFFC62828),
    infoBg        = Color(0xFFE3F2FD),
    successBg     = Color(0xFFE8F5E9),
)
