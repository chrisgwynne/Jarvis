package com.jarvis.assistant.ui.settings.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.remote.openclaw.OpenClawConnectionStatus
import com.jarvis.assistant.ui.SettingsViewModel
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsDropdownRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsPrimaryButton
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsSliderRow
import com.jarvis.assistant.ui.settings.SettingsTextFieldRow
import com.jarvis.assistant.ui.settings.SettingsTheme
import com.jarvis.assistant.ui.settings.SettingsToggleRow

@Composable
internal fun AdvancedSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    val provider         by vm.llmProvider.collectAsStateWithLifecycle()
    val apiKey           by vm.apiKey.collectAsStateWithLifecycle()
    val ollamaUrl        by vm.ollamaBaseUrl.collectAsStateWithLifecycle()
    val miniMaxUrl       by vm.miniMaxBaseUrl.collectAsStateWithLifecycle()
    val miniMaxModel     by vm.miniMaxModel.collectAsStateWithLifecycle()
    val maxTokens        by vm.maxTokens.collectAsStateWithLifecycle()
    val fallbackProvider by vm.fallbackProvider.collectAsStateWithLifecycle()

    val openAiClientId   by vm.openAiClientId.collectAsStateWithLifecycle()
    val openAiSignedIn   by vm.openAiSignedIn.collectAsStateWithLifecycle()
    val openAiError      by vm.openAiOAuthError.collectAsStateWithLifecycle()

    val openClawEnabled   by vm.openClawEnabled.collectAsStateWithLifecycle()
    val openClawHost      by vm.openClawHost.collectAsStateWithLifecycle()
    val openClawPort      by vm.openClawPort.collectAsStateWithLifecycle()
    val openClawSecure    by vm.openClawSecure.collectAsStateWithLifecycle()
    val openClawAuthToken by vm.openClawAuthToken.collectAsStateWithLifecycle()
    val openClawTimeoutMs by vm.openClawTimeoutMs.collectAsStateWithLifecycle()
    val openClawKeyword   by vm.openClawKeyword.collectAsStateWithLifecycle()
    val openClawModel     by vm.openClawModel.collectAsStateWithLifecycle()
    val openClawStatus       by vm.openClawConnectionStatus.collectAsStateWithLifecycle()
    val openClawStatusDetail by vm.openClawStatusDetail.collectAsStateWithLifecycle()
    val openClawNodeEnabled  by vm.openClawNodeEnabled.collectAsStateWithLifecycle()
    val openClawNodeStatus   by vm.openClawNodeStatus.collectAsStateWithLifecycle()

    val hermesEnabled  by vm.hermesEnabled.collectAsStateWithLifecycle()
    val hermesHost     by vm.hermesHost.collectAsStateWithLifecycle()
    val hermesPort     by vm.hermesPort.collectAsStateWithLifecycle()
    val hermesSecure   by vm.hermesSecure.collectAsStateWithLifecycle()
    val hermesApiKey   by vm.hermesApiKey.collectAsStateWithLifecycle()
    val hermesProfile  by vm.hermesProfile.collectAsStateWithLifecycle()
    val hermesStatus   by vm.hermesStatus.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Advanced", onBack = onBack, onClose = onClose) {

        /* ── LLM provider ────────────────────────────────────────────────── */
        SettingsGroup(
            title  = "LLM provider",
            footer = "Jarvis uses this provider for chat, planning and tool use.",
        ) {
            SettingsDropdownRow(
                title      = "Provider",
                options    = vm.providers,
                selected   = provider,
                label      = { it },
                onSelected = vm::setLlmProvider,
            )
            SettingsRowDivider()
            val fallbackOptions = listOf("") + vm.providers
            SettingsDropdownRow(
                title       = "Fallback provider",
                description = "Used if the primary provider fails. Disabled by default.",
                options     = fallbackOptions,
                selected    = fallbackProvider,
                label       = { if (it.isBlank()) "Disabled" else it },
                onSelected  = vm::setFallbackProvider,
            )
        }

        /* ── Response tuning ─────────────────────────────────────────────── */
        SettingsGroup(
            title  = "Response tuning",
            footer = "1200 is a good default. Raise for long-form; lower for snappier replies.",
        ) {
            SettingsSliderRow(
                title       = "Max response tokens",
                value       = maxTokens.toFloat(),
                onValueChange = { vm.setMaxTokens(it.toInt()) },
                valueRange  = 400f..4000f,
                steps       = 35,
                valueLabel  = { it.toInt().toString() },
            )
        }

        when (provider) {
            "OpenAI" -> {
                SettingsGroup(title = "OpenAI account") {
                    if (openAiSignedIn) {
                        SettingsInfoCard(
                            title      = "Connected",
                            body       = "Jarvis is signed in to OpenAI via OAuth.",
                            accent     = SettingsTheme.Success,
                            background = SettingsTheme.SuccessBg,
                        )
                        SettingsRowDivider()
                        SettingsActionRow(
                            title       = "Sign out",
                            description = "Revokes the access token on this device.",
                            actionLabel = "Sign out of OpenAI",
                            destructive = true,
                            confirm     = true,
                            confirmCopy = "Sign out",
                            onAction    = vm::signOutOpenAi,
                        )
                    } else {
                        SettingsInfoCard(
                            title = "Sign in with OpenAI",
                            body  = "Register an OAuth app at platform.openai.com to get a " +
                                    "client ID. Set the redirect URI to " +
                                    "com.jarvis.assistant://oauth/callback.",
                        )
                        SettingsRowDivider()
                        SettingsTextFieldRow(
                            title        = "OpenAI client ID",
                            value        = openAiClientId,
                            onValueChange = vm::setOpenAiClientId,
                            placeholder  = "app_…",
                        )
                        if (!openAiError.isNullOrBlank()) {
                            Text(
                                openAiError ?: "",
                                color = SettingsTheme.Destructive,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            )
                        }
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            SettingsPrimaryButton(
                                label   = "Sign in with OpenAI",
                                onClick = {
                                    val uri = vm.buildSignInUri()
                                    if (uri != null) {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    }
                                },
                            )
                        }
                    }
                }
            }

            "Ollama" -> {
                SettingsGroup(
                    title  = "Ollama",
                    footer = "Point Jarvis at your local Ollama server. No API key needed.",
                ) {
                    SettingsTextFieldRow(
                        title         = "Base URL",
                        value         = ollamaUrl,
                        onValueChange = vm::setOllamaBaseUrl,
                        placeholder   = "http://192.168.1.1:11434",
                    )
                }
            }

            "MiniMax" -> {
                SettingsGroup(
                    title  = "MiniMax",
                    footer = "International: api.minimax.io. China platform: api.minimax.chat/v1.",
                ) {
                    SettingsTextFieldRow(
                        title         = "API key",
                        value         = apiKey,
                        onValueChange = vm::setApiKey,
                        placeholder   = "sk-…",
                        isSecret      = true,
                    )
                    SettingsRowDivider()
                    SettingsTextFieldRow(
                        title         = "Base URL",
                        value         = miniMaxUrl,
                        onValueChange = vm::setMiniMaxBaseUrl,
                        placeholder   = "https://api.minimax.io/v1",
                    )
                    SettingsRowDivider()
                    SettingsTextFieldRow(
                        title         = "Model",
                        value         = miniMaxModel,
                        onValueChange = vm::setMiniMaxModel,
                        placeholder   = "MiniMax-M2.7",
                    )
                }
            }

            else -> {
                SettingsGroup(title = "API key") {
                    SettingsTextFieldRow(
                        title         = "$provider API key",
                        value         = apiKey,
                        onValueChange = vm::setApiKey,
                        placeholder   = "sk-…",
                        isSecret      = true,
                    )
                }
            }
        }

        /* ── Hermes Agent ───────────────────────────────────────────────── */
        SettingsGroup(
            title  = "Hermes Agent",
            footer = "Self-hosted LLM agent on your LAN or Tailscale. Uses the OpenAI-compatible /v1 endpoint.",
        ) {
            SettingsToggleRow(
                title           = "Enable Hermes",
                description     = "Route queries to your Hermes Agent server.",
                checked         = hermesEnabled,
                onCheckedChange = vm::setHermesEnabled,
            )
            if (hermesEnabled) {
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Host",
                    description   = "Tailscale IP or LAN address — no http:// prefix.",
                    value         = hermesHost,
                    onValueChange = vm::setHermesHost,
                    placeholder   = "100.x.x.x or mypc.tail…net",
                )
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Port",
                    value         = hermesPort,
                    onValueChange = vm::setHermesPort,
                    placeholder   = "8000",
                    keyboardType  = KeyboardType.Number,
                )
                SettingsRowDivider()
                SettingsToggleRow(
                    title           = "Secure (https)",
                    description     = "Enable if the server uses TLS.",
                    checked         = hermesSecure,
                    onCheckedChange = vm::setHermesSecure,
                )
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "API key",
                    description   = "API_SERVER_KEY set in your Hermes config.",
                    value         = hermesApiKey,
                    onValueChange = vm::setHermesApiKey,
                    placeholder   = "your-hermes-api-key",
                    isSecret      = true,
                )
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Profile / model name",
                    description   = "Advertised model name (default: hermes-agent).",
                    value         = hermesProfile,
                    onValueChange = vm::setHermesProfile,
                    placeholder   = "hermes-agent",
                )
                SettingsRowDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Connection status", color = SettingsTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        val statusText = hermesStatus ?: "Not tested"
                        val statusColor = when {
                            statusText == "Connected"      -> SettingsTheme.Success
                            statusText == "Connecting…"    -> SettingsTheme.Cyan
                            statusText == "Not tested"     -> SettingsTheme.TextMuted
                            else                           -> SettingsTheme.Destructive
                        }
                        Text(statusText, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    SettingsPrimaryButton(
                        label   = if (hermesStatus == "Connecting…") "Testing…" else "Test connection",
                        enabled = hermesStatus != "Connecting…",
                        onClick = vm::testHermesConnection,
                    )
                }
            }
        }

        /* ── OpenClaw remote backend ─────────────────────────────────────── */
        SettingsGroup(
            title  = "OpenClaw remote",
            footer = "Offload heavy tasks to a server running OpenClaw on your LAN or over Tailscale.",
        ) {
            SettingsToggleRow(
                title           = "Enable OpenClaw",
                description     = "Route complex queries to your computer.",
                checked         = openClawEnabled,
                onCheckedChange = vm::setOpenClawEnabled,
            )

            if (openClawEnabled) {
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Host",
                    description   = "Tailscale address or LAN IP.",
                    value         = openClawHost,
                    onValueChange = vm::setOpenClawHost,
                    placeholder   = "100.x.x.x or mypc.tail…net",
                )
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Port",
                    value         = openClawPort,
                    onValueChange = vm::setOpenClawPort,
                    placeholder   = "8765",
                    keyboardType  = KeyboardType.Number,
                )
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Timeout (ms)",
                    value         = openClawTimeoutMs,
                    onValueChange = vm::setOpenClawTimeoutMs,
                    placeholder   = "30000",
                    keyboardType  = KeyboardType.Number,
                )
                SettingsRowDivider()
                SettingsToggleRow(
                    title           = "Secure (wss/https)",
                    description     = "Enable if the server uses TLS.",
                    checked         = openClawSecure,
                    onCheckedChange = vm::setOpenClawSecure,
                )
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Auth token",
                    value         = openClawAuthToken,
                    onValueChange = vm::setOpenClawAuthToken,
                    placeholder   = "your-secret-token",
                    isSecret      = true,
                )
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Keyword trigger",
                    description   = "Say this word first to always route to OpenClaw " +
                                    "(e.g. \"computer, who invented the internet\").",
                    value         = openClawKeyword,
                    onValueChange = vm::setOpenClawKeyword,
                    placeholder   = "computer",
                )
                SettingsRowDivider()
                SettingsTextFieldRow(
                    title         = "Model name",
                    value         = openClawModel,
                    onValueChange = vm::setOpenClawModel,
                    placeholder   = "openclaw/default",
                )
                SettingsRowDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Connection status", color = SettingsTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text = openClawStatus.displayLabel,
                            color = statusColor(openClawStatus),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (openClawStatusDetail.isNotBlank()) {
                        Text(
                            text = openClawStatusDetail,
                            color = SettingsTheme.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    SettingsPrimaryButton(
                        label   = if (openClawStatus == OpenClawConnectionStatus.CONNECTING) "Testing…" else "Test connection",
                        enabled = openClawStatus != OpenClawConnectionStatus.CONNECTING,
                        onClick = vm::testOpenClawConnection,
                    )
                }
                SettingsRowDivider()
                // ── Node mode ──────────────────────────────────────────────
                SettingsToggleRow(
                    title       = "Register as OpenClaw node",
                    description = "Let the OpenClaw gateway invoke Jarvis tools remotely",
                    checked     = openClawNodeEnabled,
                    onCheckedChange = vm::setOpenClawNodeEnabled,
                )
                if (openClawNodeEnabled) {
                    SettingsRowDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Node status", color = SettingsTheme.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            text  = openClawNodeStatus.displayLabel,
                            color = nodeStatusColor(openClawNodeStatus),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (openClawNodeStatus == com.jarvis.assistant.remote.openclaw.OpenClawNodeStatus.PENDING_APPROVAL) {
                        Text(
                            text = "Run: openclaw devices approve <requestId> on the gateway to complete pairing",
                            color = SettingsTheme.TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun statusColor(status: OpenClawConnectionStatus) = when (status) {
    OpenClawConnectionStatus.CONNECTED        -> SettingsTheme.Success
    OpenClawConnectionStatus.CONNECTING       -> SettingsTheme.Cyan
    OpenClawConnectionStatus.AUTH_FAILED,
    OpenClawConnectionStatus.UNREACHABLE,
    OpenClawConnectionStatus.TIMED_OUT,
    OpenClawConnectionStatus.INVALID_RESPONSE -> SettingsTheme.Destructive
    OpenClawConnectionStatus.NOT_CONFIGURED   -> SettingsTheme.TextMuted
}

private fun nodeStatusColor(status: com.jarvis.assistant.remote.openclaw.OpenClawNodeStatus) = when (status) {
    com.jarvis.assistant.remote.openclaw.OpenClawNodeStatus.CONNECTED         -> SettingsTheme.Success
    com.jarvis.assistant.remote.openclaw.OpenClawNodeStatus.CONNECTING,
    com.jarvis.assistant.remote.openclaw.OpenClawNodeStatus.RECONNECTING,
    com.jarvis.assistant.remote.openclaw.OpenClawNodeStatus.PENDING_APPROVAL  -> SettingsTheme.Cyan
    com.jarvis.assistant.remote.openclaw.OpenClawNodeStatus.ERROR             -> SettingsTheme.Destructive
    com.jarvis.assistant.remote.openclaw.OpenClawNodeStatus.DISABLED          -> SettingsTheme.TextMuted
}
