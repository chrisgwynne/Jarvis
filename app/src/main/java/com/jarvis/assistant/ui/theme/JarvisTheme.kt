package com.jarvis.assistant.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Theme variants the user can select from Settings → Appearance.
 *
 * SYSTEM follows the OS dark-mode setting; AMOLED is a strict-black variant
 * that matters for an always-on app on OLED panels.
 */
enum class JarvisThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/**
 * Public theme entry point.  All Compose surfaces should wrap their content
 * in this composable rather than calling MaterialTheme directly so that
 * brand colours, dynamic-color preferences, system-bar tinting and the
 * Jarvis-specific extra colours all stay in sync.
 *
 * @param mode           User-selected theme mode.  SYSTEM defers to the OS
 *                       dark-mode flag and never selects AMOLED implicitly.
 * @param dynamicColor   When true and Android 12+, derives the colour scheme
 *                       from the user's wallpaper.  AMOLED ignores this and
 *                       always uses true black.
 * @param systemDark     The OS dark-mode flag.  Defaults to the platform
 *                       value via [androidx.compose.foundation.isSystemInDarkTheme].
 */
@Composable
fun JarvisTheme(
    mode: JarvisThemeMode = JarvisThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    systemDark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val isDark = when (mode) {
        JarvisThemeMode.SYSTEM -> systemDark
        JarvisThemeMode.LIGHT  -> false
        JarvisThemeMode.DARK,
        JarvisThemeMode.AMOLED -> true
    }

    val colorScheme = when {
        // AMOLED always wins — true-black is the whole point of the variant.
        mode == JarvisThemeMode.AMOLED -> AmoledColorScheme

        // Dynamic colour (Android 12+) — only when the user opted in and we
        // aren't in AMOLED mode.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }

        isDark -> DarkColorScheme
        else   -> LightColorScheme
    }

    val extras = when {
        mode == JarvisThemeMode.AMOLED -> AmoledExtras
        isDark -> DarkExtras
        else   -> LightExtras
    }

    // Tint the system bars to match the surface and flip the icon brightness
    // so the bars stay legible on whichever scheme is active.  Done in a
    // SideEffect because it touches the host Activity's Window.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val barColour = colorScheme.background.toArgb()
            window.statusBarColor = barColour
            window.navigationBarColor = barColour
            val lightBars = colorScheme.background.luminance() > 0.5f
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    CompositionLocalProvider(LocalJarvisExtraColors provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = JarvisTypography,
            content     = content,
        )
    }
}

/** Convenience accessor for the Jarvis-specific extras inside a composable. */
val MaterialTheme.jarvisExtras: JarvisExtraColors
    @Composable get() = LocalJarvisExtraColors.current
