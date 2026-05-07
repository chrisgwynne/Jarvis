package com.jarvis.assistant.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.JarvisTokens
import com.jarvis.assistant.ui.theme.jarvisExtras
import kotlinx.coroutines.delay

/**
 * Surfaces an actionable warning when the OS or OEM has put the foreground
 * service at risk of being killed.
 *
 * Two checks today:
 *   1. Battery optimisation is NOT exempted — the foreground service can
 *      still be killed in Doze on aggressive OEMs.
 *   2. The service was running in the recent past but isn't now (a kill
 *      indicator) — surfaces a one-tap link to dontkillmyapp.com guidance
 *      tailored to the user's manufacturer.
 *
 * Composable, no side-effects beyond reading PowerManager.  The actual
 * service kill detection is a process-lifetime AtomicLong written by
 * [com.jarvis.assistant.service.JarvisService] in onCreate / onDestroy
 * — this composable polls it every few seconds.
 */
@Composable
fun ServiceHealthBanner(
    isRunning: Boolean,
) {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }

    var batteryOptimised by remember {
        mutableStateOf(pm?.isIgnoringBatteryOptimizations(context.packageName) == false)
    }
    LaunchedEffect(isRunning) {
        // Re-check on each run/stop event — the user may have just granted it.
        while (true) {
            batteryOptimised = pm?.isIgnoringBatteryOptimizations(context.packageName) == false
            delay(5_000)
        }
    }

    if (!batteryOptimised) return

    val extras = MaterialTheme.jarvisExtras
    val brandColour = extras.statusAmber
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = JarvisTokens.Space.lg, vertical = JarvisTokens.Space.sm)
            .border(
                width = 1.dp,
                color = brandColour.copy(alpha = 0.4f),
                shape = JarvisTokens.Shape.row,
            )
            .background(brandColour.copy(alpha = 0.06f), JarvisTokens.Shape.row)
            .clickable {
                val manufacturer = android.os.Build.MANUFACTURER.lowercase()
                val url = "https://dontkillmyapp.com/${manufacturer}"
                val fallback = "https://dontkillmyapp.com"
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .padding(JarvisTokens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JarvisTokens.Space.md),
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = null,
            tint = brandColour,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "Battery optimisation is on",
                style = MaterialTheme.typography.titleSmall,
                color = brandColour,
            )
            Text(
                text  = "Android may kill Jarvis when the screen turns off. " +
                        "Tap for OEM-specific guidance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
