package com.jarvis.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * JarvisTypography — Material 3 type scale with the brand's monospace
 * accent for status / brand chrome.
 *
 * Body text uses the default sans-serif so dynamic font scaling works
 * out of the box (TalkBack and Settings → Display → Font size both
 * respect this).  The monospace accent is exposed as [JarvisTextStyles]
 * for screens that want the deliberate retro/HUD feel.
 */
internal val JarvisTypography = Typography(
    displayLarge   = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold,    letterSpacing = 0.sp),
    displayMedium  = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold,    letterSpacing = 0.sp),
    headlineLarge  = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleLarge     = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleMedium    = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium,   letterSpacing = 0.1.sp),
    titleSmall     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium,   letterSpacing = 0.1.sp),
    bodyLarge      = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal,   letterSpacing = 0.5.sp, lineHeight = 22.sp),
    bodyMedium     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal,   letterSpacing = 0.25.sp, lineHeight = 20.sp),
    bodySmall      = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal,   letterSpacing = 0.4.sp,  lineHeight = 16.sp),
    labelLarge     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium,   letterSpacing = 0.1.sp),
    labelMedium    = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,   letterSpacing = 0.5.sp),
    labelSmall     = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium,   letterSpacing = 0.5.sp),
)

/**
 * Brand-specific accent styles — monospace, wide letter-spacing, used for
 * status chips, the JARVIS wordmark, and the orb status label.
 */
object JarvisTextStyles {
    val brandWordmark = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontSize      = 20.sp,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 5.sp,
    )
    val brandSubline = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontSize      = 9.sp,
        letterSpacing = 3.sp,
    )
    val statusChip = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontSize      = 11.sp,
        fontWeight    = FontWeight.SemiBold,
        letterSpacing = 2.sp,
    )
    val actionButton = TextStyle(
        fontFamily    = FontFamily.Monospace,
        fontSize      = 13.sp,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 2.sp,
    )
}
