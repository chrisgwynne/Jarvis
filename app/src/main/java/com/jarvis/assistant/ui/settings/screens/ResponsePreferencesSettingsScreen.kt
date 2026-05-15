package com.jarvis.assistant.ui.settings.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.preferences.ResponseDomain
import com.jarvis.assistant.preferences.ResponsePreference
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTheme
import kotlinx.coroutines.launch

/**
 * Response Preferences settings — shows all stored per-domain formatting
 * preferences and lets the user reset individual domains or clear all.
 *
 * Preferences are taught conversationally ("I just prefer condition and degrees
 * for weather"), not edited here — this screen is a diagnostic / management view.
 */
@Composable
internal fun ResponsePreferencesSettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val engine = try { JarvisApp.preferenceEngine } catch (_: Throwable) { null }

    val preferences by produceState<List<ResponsePreference>>(
        initialValue = emptyList(),
        engine,
    ) {
        value = engine?.getAll() ?: emptyList()
    }
    var statusText by remember { mutableStateOf("") }

    SettingsScaffold(title = "Response Preferences", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "How it works",
            body  = "Say things like \"I just prefer condition and degrees for weather\" or " +
                    "\"next time keep calendar brief\" and Jarvis will remember. " +
                    "Preferences are applied locally — no LLM needed. " +
                    "This screen lets you review and reset what's stored."
        )

        // ── Active preferences by domain ─────────────────────────────────────
        val activePrefs = preferences.filter { it.isActive() }
        if (activePrefs.isNotEmpty()) {
            val byDomain = activePrefs.groupBy { it.domain }
            byDomain.entries.sortedBy { it.key.displayName }.forEach { (domain, prefs) ->
                SettingsGroup {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text       = domain.displayName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 13.sp,
                            color      = SettingsTheme.TextMuted,
                        )
                        prefs.forEach { pref ->
                            val ruleLabel = when {
                                pref.includeFields.isNotEmpty() ->
                                    "Only: ${pref.includeFields.joinToString(", ")}"
                                pref.excludeFields.isNotEmpty()  ->
                                    "Exclude: ${pref.excludeFields.joinToString(", ")}"
                                pref.ruleType.name == "LENGTH"   ->
                                    "Length: ${pref.preferredLength.displayLabel}"
                                else ->
                                    "Style: ${pref.sourceUtterance.take(60)}"
                            }
                            Text(
                                text     = "• $ruleLabel",
                                fontSize = 12.sp,
                                color    = SettingsTheme.TextPrimary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    SettingsRowDivider()
                    SettingsActionRow(
                        title       = "Reset ${domain.displayName}",
                        description = "Remove stored preference for this domain",
                        actionLabel = "Reset",
                    ) {
                        scope.launch {
                            engine?.resetDomain(domain)
                            statusText = "${domain.displayName} preference cleared."
                            Toast.makeText(context, statusText, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text     = "No preferences stored yet.\nTell Jarvis how you prefer responses " +
                                   "and they'll appear here.",
                        fontSize = 13.sp,
                        color    = SettingsTheme.TextPrimary,
                    )
                }
            }
        }

        // ── Disabled / inactive preferences ──────────────────────────────────
        val inactivePrefs = preferences.filter { !it.isActive() }
        if (inactivePrefs.isNotEmpty()) {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text       = "Inactive (low confidence or disabled)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp,
                        color      = SettingsTheme.TextMuted,
                    )
                    inactivePrefs.forEach { pref ->
                        Text(
                            text     = "• ${pref.domain.displayName} — ${pref.sourceUtterance.take(60)}",
                            fontSize = 11.sp,
                            color    = SettingsTheme.TextMuted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (statusText.isNotBlank()) {
            SettingsGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(text = statusText, fontSize = 12.sp, color = SettingsTheme.TextMuted)
                }
            }
        }

        // ── Actions ──────────────────────────────────────────────────────────
        SettingsGroup {
            SettingsActionRow(
                title       = "Clear all preferences",
                description = "Remove every stored formatting preference",
                actionLabel = "Clear all",
            ) {
                scope.launch {
                    engine?.resetAll()
                    statusText = "All response preferences cleared."
                    Toast.makeText(context, statusText, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
