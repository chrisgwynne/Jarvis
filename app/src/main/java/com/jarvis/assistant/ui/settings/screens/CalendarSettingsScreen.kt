package com.jarvis.assistant.ui.settings.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.content.ContentUris
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TAG = "CalendarSettings"

/**
 * CalendarSettingsScreen — shows calendar connection status, available
 * calendars, and a diagnostic query button.
 *
 * No toggles to configure here: Jarvis always uses the Android Calendar
 * Provider (CalendarContract.Instances) for all queries.  This screen
 * is purely diagnostic — the user can see what Jarvis sees and run a
 * test query to verify events are readable.
 */
@Composable
internal fun CalendarSettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var diagResult by remember { mutableStateOf<DiagResult?>(null) }
    var isRunning  by remember { mutableStateOf(false) }

    SettingsScaffold(title = "Calendar", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Android Calendar Provider",
            body  = "Jarvis reads from the Calendar app already synced to your phone. " +
                "No separate Google sign-in is needed — sync your Google Calendar " +
                "account in Android Settings first.",
        )

        // ── Permission status ──────────────────────────────────────────────
        SettingsGroup(title = "Permissions") {
            val granted = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED
            val writeGranted = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

            PermRow("Read calendar",  granted)
            SettingsRowDivider()
            PermRow("Write calendar", writeGranted)
        }

        Spacer(Modifier.height(8.dp))

        // ── Calendar accounts ──────────────────────────────────────────────
        val calendars = remember { fetchCalendars(context) }
        SettingsGroup(
            title       = "Calendars on device",
            description = "${calendars.size} calendar(s) found",
        ) {
            if (calendars.isEmpty()) {
                Text(
                    text = "No calendars found. Add a Google account in Android Settings " +
                        "and enable Calendar sync.",
                    fontSize = 13.sp,
                    color    = SettingsTheme.TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                calendars.forEachIndexed { i, cal ->
                    CalendarRow(cal)
                    if (i < calendars.lastIndex) SettingsRowDivider()
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Diagnostic test ────────────────────────────────────────────────
        SettingsGroup(
            title       = "Test calendar query",
            description = "Run today's event query and show raw results",
        ) {
            SettingsActionRow(
                title       = "Test today's events",
                description = "Queries CalendarContract.Instances for today",
                actionLabel = if (isRunning) "Running…" else "Run test",
                onAction    = {
                    if (!isRunning) {
                        isRunning = true
                        scope.launch {
                            diagResult = runDiagnostic(context)
                            isRunning = false
                        }
                    }
                },
            )

            diagResult?.let { r ->
                SettingsRowDivider()
                DiagOutput(r)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────────────────────

private data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val isPrimary: Boolean,
    val visible: Boolean,
)

private data class DiagResult(
    val permGranted: Boolean,
    val rawCount: Int,
    val filteredCount: Int,
    val events: List<String>,
    val error: String?,
    val ranAtMs: Long = System.currentTimeMillis(),
)

// ─────────────────────────────────────────────────────────────────────────────
// Composable helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermRow(label: String, granted: Boolean) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text      = if (granted) "Granted" else "Denied",
            fontSize  = 13.sp,
            color     = if (granted) SettingsTheme.Cyan else SettingsTheme.Destructive,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CalendarRow(cal: CalendarInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text      = cal.name,
            fontSize  = 15.sp,
            fontWeight = if (cal.isPrimary) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text     = "${cal.accountName}${if (cal.isPrimary) " · Primary" else ""}${if (!cal.visible) " · Hidden" else ""}",
            fontSize = 12.sp,
            color    = SettingsTheme.TextMuted,
        )
    }
}

@Composable
private fun DiagOutput(r: DiagResult) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text      = "Result at ${timeFmt.format(Date(r.ranAtMs))}",
            fontSize  = 11.sp,
            color     = SettingsTheme.TextMuted,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))

        DiagLine("Permission", if (r.permGranted) "Granted" else "Denied", r.permGranted)
        DiagLine("Raw events returned", r.rawCount.toString(), r.rawCount > 0 || r.filteredCount == 0)
        DiagLine("Filtered events (shown to user)", r.filteredCount.toString(), true)

        if (!r.permGranted) {
            Text(
                text     = "Grant calendar permission in Android Settings > Apps > Jarvis",
                fontSize = 12.sp,
                color    = SettingsTheme.Destructive,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        r.error?.let { err ->
            Text(
                text     = "Error: $err",
                fontSize = 12.sp,
                color    = SettingsTheme.Destructive,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (r.events.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text      = "Events found today:",
                fontSize  = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color     = SettingsTheme.TextMuted,
            )
            r.events.forEach { ev ->
                Text(
                    text     = "• $ev",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        } else if (r.permGranted && r.rawCount == 0) {
            Text(
                text     = "No events found today — calendar appears empty for today.",
                fontSize = 12.sp,
                color    = SettingsTheme.TextMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String, ok: Boolean) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(text = "$label: ", fontSize = 12.sp, color = SettingsTheme.TextMuted, modifier = Modifier.weight(1f))
        Text(
            text   = value,
            fontSize = 12.sp,
            color  = if (ok) SettingsTheme.Cyan else SettingsTheme.Destructive,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data helpers (run on IO dispatcher)
// ─────────────────────────────────────────────────────────────────────────────

private fun fetchCalendars(context: Context): List<CalendarInfo> {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
        PackageManager.PERMISSION_GRANTED
    ) return emptyList()
    return try {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
        )
        val out = mutableListOf<CalendarInfo>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj, null, null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    id          = c.getLong(0),
                    name        = c.getString(1) ?: "Unnamed",
                    accountName = c.getString(2) ?: "",
                    isPrimary   = c.getInt(3) == 1,
                    visible     = c.getInt(4) == 1,
                )
            }
        }
        out
    } catch (e: Exception) {
        Log.w(TAG, "fetchCalendars failed: ${e.message}")
        emptyList()
    }
}

private suspend fun runDiagnostic(context: Context): DiagResult = withContext(Dispatchers.IO) {
    val granted = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) {
        return@withContext DiagResult(
            permGranted   = false,
            rawCount      = 0,
            filteredCount = 0,
            events        = emptyList(),
            error         = "READ_CALENDAR permission not granted",
        )
    }

    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val dayStart = cal.timeInMillis
    cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
    val dayEnd = cal.timeInMillis

    return@withContext try {
        val instancesUri: Uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .also { b -> ContentUris.appendId(b, dayStart); ContentUris.appendId(b, dayEnd) }
            .build()

        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val cursor = context.contentResolver.query(
            instancesUri, proj,
            "${CalendarContract.Instances.DELETED} = 0",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )

        val rawCount = cursor?.count ?: 0
        val events   = mutableListOf<String>()
        var filtered = 0

        cursor?.use { c ->
            while (c.moveToNext()) {
                val title  = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                val begin  = c.getLong(1)
                val allDay = c.getInt(2) == 1
                val status = if (c.isNull(3)) -1 else c.getInt(3)
                if (status == 2 /* DECLINED */) continue
                filtered++
                val label = if (allDay) "$title (all-day)" else "$title at ${timeFmt.format(begin)}"
                if (events.size < 10) events += label
            }
        }

        Log.d(TAG, "[CALENDAR_DIAG] raw=$rawCount filtered=$filtered")
        DiagResult(
            permGranted   = true,
            rawCount      = rawCount,
            filteredCount = filtered,
            events        = events,
            error         = null,
        )
    } catch (e: Exception) {
        Log.e(TAG, "[CALENDAR_DIAG_FAILED] ${e.message}", e)
        DiagResult(
            permGranted   = true,
            rawCount      = 0,
            filteredCount = 0,
            events        = emptyList(),
            error         = e.message ?: e::class.simpleName,
        )
    }
}
