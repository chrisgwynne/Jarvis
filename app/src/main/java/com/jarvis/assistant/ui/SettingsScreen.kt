package com.jarvis.assistant.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.jarvis.assistant.audio.TtsEngine
import com.jarvis.assistant.remote.openclaw.OpenClawConnectionStatus
import com.jarvis.assistant.speaker.db.PersonRecord

/** Resolve the display name for the currently-selected voice key. */
private fun selectedVoiceLabel(key: String, voices: List<TtsVoiceInfo>): String = when {
    key.isBlank() -> "System default"
    else          -> voices.find { it.name == key }?.displayName ?: key
}

// ── Colours ───────────────────────────────────────────────────────────────────
private val BgDark       = Color(0xFF0D0D0D)
private val Surface      = Color(0xFF1A1A2E)
private val Cyan         = Color(0xFF00BCD4)
private val TextPrimary  = Color(0xFFE0E0E0)
private val TextMuted    = Color(0xFF888888)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel()
) {
    val context       = LocalContext.current
    val provider          by vm.llmProvider.collectAsStateWithLifecycle()
    val apiKey            by vm.apiKey.collectAsStateWithLifecycle()
    val ollamaUrl         by vm.ollamaBaseUrl.collectAsStateWithLifecycle()
    val miniMaxUrl        by vm.miniMaxBaseUrl.collectAsStateWithLifecycle()
    val miniMaxModel      by vm.miniMaxModel.collectAsStateWithLifecycle()
    val maxTokens         by vm.maxTokens.collectAsStateWithLifecycle()
    val fallbackProvider  by vm.fallbackProvider.collectAsStateWithLifecycle()
    val haBaseUrl         by vm.haBaseUrl.collectAsStateWithLifecycle()
    val haApiToken        by vm.haApiToken.collectAsStateWithLifecycle()
    val wakeWord         by vm.wakeWord.collectAsStateWithLifecycle()
    val voiceResponse    by vm.voiceResponse.collectAsStateWithLifecycle()
    val ttsVoiceName     by vm.ttsVoiceName.collectAsStateWithLifecycle()
    val availableVoices  by vm.availableVoices.collectAsStateWithLifecycle()
    val braveKey        by vm.braveSearchApiKey.collectAsStateWithLifecycle()
    val defaultMsgChannel by vm.defaultMsgChannel.collectAsStateWithLifecycle()
    val openAiClientId  by vm.openAiClientId.collectAsStateWithLifecycle()
    val openAiSignedIn  by vm.openAiSignedIn.collectAsStateWithLifecycle()
    val openAiOAuthError by vm.openAiOAuthError.collectAsStateWithLifecycle()

    val speakerProfiles    by vm.speakerProfiles.collectAsStateWithLifecycle()

    val openClawEnabled    by vm.openClawEnabled.collectAsStateWithLifecycle()
    val openClawHost       by vm.openClawHost.collectAsStateWithLifecycle()
    val openClawPort       by vm.openClawPort.collectAsStateWithLifecycle()
    val openClawSecure     by vm.openClawSecure.collectAsStateWithLifecycle()
    val openClawAuthToken  by vm.openClawAuthToken.collectAsStateWithLifecycle()
    val openClawTimeoutMs  by vm.openClawTimeoutMs.collectAsStateWithLifecycle()
    val openClawModel      by vm.openClawModel.collectAsStateWithLifecycle()
    val openClawKeyword    by vm.openClawKeyword.collectAsStateWithLifecycle()
    val openClawStatus     by vm.openClawConnectionStatus.collectAsStateWithLifecycle()
    val haConnectionStatus by vm.haConnectionStatus.collectAsStateWithLifecycle()

    var apiKeyVisible       by remember { mutableStateOf(false) }
    var providerExpanded    by remember { mutableStateOf(false) }
    var fallbackExpanded    by remember { mutableStateOf(false) }
    var voiceExpanded       by remember { mutableStateOf(false) }
    var msgChannelExpanded  by remember { mutableStateOf(false) }
    var haTokenVisible      by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // ── Top bar ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Cyan
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Settings",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        HorizontalDivider(color = Surface)

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Section: LLM ─────────────────────────────────────────────
            SectionHeader("LLM Provider")

            // Provider dropdown
            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = it }
            ) {
                OutlinedTextField(
                    value = provider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider", color = TextMuted) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false },
                    modifier = Modifier.background(Surface)
                ) {
                    vm.providers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p, color = TextPrimary) },
                            onClick = {
                                vm.setLlmProvider(p)
                                providerExpanded = false
                            }
                        )
                    }
                }
            }

            // OpenAI — OAuth sign-in instead of raw API key
            if (provider == "OpenAI") {
                OpenAiOAuthSection(
                    clientId     = openAiClientId,
                    signedIn     = openAiSignedIn,
                    errorMessage = openAiOAuthError,
                    onClientIdChange = vm::setOpenAiClientId,
                    onSignIn = {
                        val uri = vm.buildSignInUri()
                        if (uri != null) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                    },
                    onSignOut = vm::signOutOpenAi
                )
            }

            // API Key — shown for all other cloud providers (not Ollama/OpenAI)
            if (provider != "Ollama" && provider != "OpenAI") {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = vm::setApiKey,
                    label = { Text("API Key", color = TextMuted) },
                    placeholder = { Text("sk-…", color = TextMuted) },
                    visualTransformation = if (apiKeyVisible)
                        VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = "Toggle key visibility",
                                tint = TextMuted
                            )
                        }
                    },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // Ollama URL — shown only when Ollama selected
            if (provider == "Ollama") {
                OutlinedTextField(
                    value = ollamaUrl,
                    onValueChange = vm::setOllamaBaseUrl,
                    label = { Text("Ollama Base URL", color = TextMuted) },
                    placeholder = { Text("http://192.168.1.1:11434", color = TextMuted) },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // MiniMax region URL — shown only when MiniMax selected
            if (provider == "MiniMax") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D1B2A), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "MiniMax has two API regions",
                        color = Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Default URL is for platform.minimax.io (international).\n" +
                        "Current model: minimax-2.7\n" +
                        "China platform: api.minimax.chat/v1",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
                OutlinedTextField(
                    value = miniMaxUrl,
                    onValueChange = vm::setMiniMaxBaseUrl,
                    label = { Text("MiniMax Base URL", color = TextMuted) },
                    placeholder = { Text("https://api.minimax.io/v1", color = TextMuted) },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = miniMaxModel,
                    onValueChange = vm::setMiniMaxModel,
                    label = { Text("Model", color = TextMuted) },
                    placeholder = { Text("abab6.5s-chat", color = TextMuted) },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // Max tokens slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Max Response Tokens", color = TextPrimary, fontSize = 15.sp)
                    Text("$maxTokens", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = maxTokens.toFloat(),
                    onValueChange = { vm.setMaxTokens(it.toInt()) },
                    valueRange = 400f..4000f,
                    steps = 35,
                    colors = SliderDefaults.colors(
                        thumbColor = Cyan,
                        activeTrackColor = Cyan,
                        inactiveTrackColor = Surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Controls maximum length of each response. 1200 is a good default.",
                    color = TextMuted, fontSize = 11.sp
                )
            }

            // Fallback provider dropdown
            val fallbackOptions = listOf("") + vm.providers
            val fallbackLabels  = mapOf("" to "Disabled") + vm.providers.associateWith { it }
            ExposedDropdownMenuBox(
                expanded = fallbackExpanded,
                onExpandedChange = { fallbackExpanded = it }
            ) {
                OutlinedTextField(
                    value = fallbackLabels[fallbackProvider] ?: "Disabled",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Fallback Provider", color = TextMuted) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(fallbackExpanded) },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = fallbackExpanded,
                    onDismissRequest = { fallbackExpanded = false },
                    modifier = Modifier.background(Surface)
                ) {
                    fallbackOptions.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(fallbackLabels[p] ?: p, color = TextPrimary) },
                            onClick = { vm.setFallbackProvider(p); fallbackExpanded = false }
                        )
                    }
                }
            }

            HorizontalDivider(color = Surface)

            // ── Section: Voice ───────────────────────────────────────────
            SectionHeader("Voice")

            OutlinedTextField(
                value = wakeWord,
                onValueChange = vm::setWakeWord,
                label = { Text("Wake Word", color = TextMuted) },
                placeholder = { Text("Hey Jarvis", color = TextMuted) },
                colors = jarvisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Voice Response", color = TextPrimary, fontSize = 15.sp)
                    Text(
                        "Speak replies aloud via TTS",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = voiceResponse,
                    onCheckedChange = vm::setVoiceResponse,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Cyan,
                        checkedTrackColor = Cyan.copy(alpha = 0.4f)
                    )
                )
            }

            // TTS voice picker — only shown when voices have been enumerated
            if (availableVoices.isNotEmpty()) {
                val displayName = selectedVoiceLabel(ttsVoiceName, availableVoices)
                val offlineVoices = availableVoices.filter { !it.isNetwork }
                val onlineVoices  = availableVoices.filter { it.isNetwork }

                ExposedDropdownMenuBox(
                    expanded = voiceExpanded,
                    onExpandedChange = { voiceExpanded = it }
                ) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("TTS Voice", color = TextMuted) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(voiceExpanded) },
                        colors = jarvisTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = voiceExpanded,
                        onDismissRequest = { voiceExpanded = false },
                        modifier = Modifier.background(Surface)
                    ) {
                        // System default
                        DropdownMenuItem(
                            text = { Text("System default", color = TextPrimary) },
                            onClick = { vm.setTtsVoiceName(""); voiceExpanded = false }
                        )

                        // Offline voices (including Jarvis local)
                        offlineVoices.forEach { info ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            info.displayName,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Preview",
                                            tint = Cyan,
                                            modifier = Modifier.padding(start = 4.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    vm.setTtsVoiceName(info.name)
                                    vm.previewVoice(info.name)
                                    voiceExpanded = false
                                }
                            )
                        }

                        // Online voices section header
                        if (onlineVoices.isNotEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "── Online voices ──",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                },
                                onClick = {},
                                enabled = false
                            )
                            onlineVoices.forEach { info ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                info.displayName,
                                                color = TextPrimary,
                                                fontSize = 13.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "Preview",
                                                tint = Cyan,
                                                modifier = Modifier.padding(start = 4.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        vm.setTtsVoiceName(info.name)
                                        vm.previewVoice(info.name)
                                        voiceExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Hint + "Get more voices" link
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Tap to select and preview. Online voices require internet.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            // Opens Android TTS settings where the user can download
                            // additional voices (British, Irish, Australian, etc.)
                            try {
                                context.startActivity(
                                    Intent("com.android.settings.TTS_SETTINGS")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (_: Exception) {
                                context.startActivity(
                                    Intent(Settings.ACTION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text("+ More voices", color = Cyan, fontSize = 11.sp)
                    }
                }
            }

            HorizontalDivider(color = Surface)

            // ── Section: Speaker Profiles ─────────────────────────────────
            SectionHeader("Speaker Profiles")

            // Show whether the TFLite neural speaker model is active
            val context = LocalContext.current
            val neuralActive = remember {
                context.assets.list("")?.contains("speaker_encoder.tflite") == true
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Recognition engine", color = TextMuted, fontSize = 12.sp)
                Text(
                    text  = if (neuralActive) "Neural (ECAPA-TDNN)" else "MFCC fallback",
                    color = if (neuralActive) Color(0xFF00E676) else Color(0xFFFFD600),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (speakerProfiles.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D1B2A), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "No voice profiles enrolled yet",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                    Text(
                        "Say your name when Jarvis asks \"Who's this?\" to start building your voice profile.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    speakerProfiles.forEach { person ->
                        SpeakerProfileRow(
                            person   = person,
                            onDelete = { vm.deleteSpeakerProfile(person.id) },
                            onTrain  = { vm.scheduleVoiceEnrollment(person.id) }
                        )
                    }
                }
            }

            HorizontalDivider(color = Surface)

            // ── Section: Tools ───────────────────────────────────────────
            SectionHeader("Tools & Search")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1B2A), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Contacts, Calls & SMS work automatically once permissions are granted.",
                    color = Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Web search uses DuckDuckGo (free, no key).\n" +
                    "For richer results add a Brave Search key (free at search.brave.com/settings).",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            var braveKeyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = braveKey,
                onValueChange = vm::setBraveSearchApiKey,
                label = { Text("Brave Search API Key (optional)", color = TextMuted) },
                placeholder = { Text("BSA…", color = TextMuted) },
                visualTransformation = if (braveKeyVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { braveKeyVisible = !braveKeyVisible }) {
                        Icon(
                            if (braveKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle visibility",
                            tint = TextMuted
                        )
                    }
                },
                colors = jarvisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider(color = Surface)

            // ── Section: Messaging ───────────────────────────────────────
            SectionHeader("Messaging")

            val msgChannelOptions = listOf("ask", "sms", "whatsapp")
            val msgChannelLabels  = mapOf("ask" to "Ask each time", "sms" to "SMS", "whatsapp" to "WhatsApp")

            ExposedDropdownMenuBox(
                expanded = msgChannelExpanded,
                onExpandedChange = { msgChannelExpanded = it }
            ) {
                OutlinedTextField(
                    value = msgChannelLabels[defaultMsgChannel] ?: "Ask each time",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Default channel", color = TextMuted) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(msgChannelExpanded) },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = msgChannelExpanded,
                    onDismissRequest = { msgChannelExpanded = false },
                    modifier = Modifier.background(Surface)
                ) {
                    msgChannelOptions.forEach { ch ->
                        DropdownMenuItem(
                            text = { Text(msgChannelLabels[ch] ?: ch, color = TextPrimary) },
                            onClick = { vm.setDefaultMsgChannel(ch); msgChannelExpanded = false }
                        )
                    }
                }
            }

            HorizontalDivider(color = Surface)

            // ── Section: OpenClaw ─────────────────────────────────────────
            SectionHeader("OpenClaw Remote")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Enable OpenClaw", color = TextPrimary, fontSize = 15.sp)
                    Text(
                        "Route complex queries to your computer",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = openClawEnabled,
                    onCheckedChange = vm::setOpenClawEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Cyan,
                        checkedTrackColor = Cyan.copy(alpha = 0.4f)
                    )
                )
            }

            if (openClawEnabled) {
                OutlinedTextField(
                    value = openClawHost,
                    onValueChange = vm::setOpenClawHost,
                    label = { Text("Host (Tailscale address or IP)", color = TextMuted) },
                    placeholder = { Text("100.x.x.x  or  mypc.tail…net", color = TextMuted) },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = openClawPort,
                        onValueChange = vm::setOpenClawPort,
                        label = { Text("Port", color = TextMuted) },
                        placeholder = { Text("8765", color = TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = jarvisTextFieldColors(),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = openClawTimeoutMs,
                        onValueChange = vm::setOpenClawTimeoutMs,
                        label = { Text("Timeout (ms)", color = TextMuted) },
                        placeholder = { Text("30000", color = TextMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = jarvisTextFieldColors(),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Secure (wss / https)", color = TextPrimary, fontSize = 15.sp)
                        Text("Enable if your server uses TLS", color = TextMuted, fontSize = 12.sp)
                    }
                    Switch(
                        checked = openClawSecure,
                        onCheckedChange = vm::setOpenClawSecure,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Cyan,
                            checkedTrackColor = Cyan.copy(alpha = 0.4f)
                        )
                    )
                }

                var tokenVisible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = openClawAuthToken,
                    onValueChange = vm::setOpenClawAuthToken,
                    label = { Text("Auth Token", color = TextMuted) },
                    placeholder = { Text("your-secret-token", color = TextMuted) },
                    visualTransformation = if (tokenVisible)
                        VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = "Toggle token visibility",
                                tint = TextMuted
                            )
                        }
                    },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = openClawKeyword,
                    onValueChange = vm::setOpenClawKeyword,
                    label = { Text("Keyword trigger", color = TextMuted) },
                    placeholder = { Text("computer", color = TextMuted) },
                    supportingText = { Text("Say this word first to always invoke OpenClaw (e.g. \"computer, who invented the internet\")", color = TextMuted, fontSize = 11.sp) },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = openClawModel,
                    onValueChange = vm::setOpenClawModel,
                    label = { Text("Model name", color = TextMuted) },
                    placeholder = { Text("openclaw", color = TextMuted) },
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Test Connection button + status indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = vm::testOpenClawConnection,
                        enabled = openClawStatus != OpenClawConnectionStatus.CONNECTING,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1A1A2E),
                            contentColor = Cyan
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Cyan),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (openClawStatus == OpenClawConnectionStatus.CONNECTING)
                                "Testing…" else "Test Connection",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    val statusColor = when (openClawStatus) {
                        OpenClawConnectionStatus.CONNECTED        -> Color(0xFF00E676)
                        OpenClawConnectionStatus.CONNECTING       -> Cyan
                        OpenClawConnectionStatus.AUTH_FAILED,
                        OpenClawConnectionStatus.UNREACHABLE,
                        OpenClawConnectionStatus.TIMED_OUT,
                        OpenClawConnectionStatus.INVALID_RESPONSE -> Color(0xFFFF5252)
                        OpenClawConnectionStatus.NOT_CONFIGURED   -> TextMuted
                    }
                    Text(
                        openClawStatus.displayLabel,
                        color = statusColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider(color = Surface)

            // ── Section: Smart Home ──────────────────────────────────────
            SectionHeader("Smart Home (Home Assistant)")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1B2A), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Connect to a local Home Assistant instance to control lights, switches, locks, and thermostats by voice.",
                    color = TextMuted, fontSize = 11.sp
                )
            }

            OutlinedTextField(
                value = haBaseUrl,
                onValueChange = vm::setHaBaseUrl,
                label = { Text("Base URL", color = TextMuted) },
                placeholder = { Text("http://homeassistant.local:8123", color = TextMuted) },
                colors = jarvisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = haApiToken,
                onValueChange = vm::setHaApiToken,
                label = { Text("Long-Lived Access Token", color = TextMuted) },
                placeholder = { Text("your-ha-token", color = TextMuted) },
                visualTransformation = if (haTokenVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { haTokenVisible = !haTokenVisible }) {
                        Icon(
                            if (haTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle token visibility",
                            tint = TextMuted
                        )
                    }
                },
                colors = jarvisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = vm::testHaConnection,
                    enabled = haConnectionStatus != "Connecting…",
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0D1B2A),
                        contentColor = Cyan
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Cyan),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (haConnectionStatus == "Connecting…") "Testing…" else "Test Connection",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                haConnectionStatus?.let { status ->
                    Text(
                        text = status,
                        color = if (status.startsWith("Connected")) Color(0xFF00E676) else if (status == "Connecting…") Cyan else Color(0xFFFF5252),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // ── Memory ────────────────────────────────────────────────────────
            Spacer(Modifier.height(24.dp))
            SectionHeader("Memory")
            Spacer(Modifier.height(12.dp))

            var confirmClearMemories    by remember { mutableStateOf(false) }
            var confirmClearHistory     by remember { mutableStateOf(false) }

            // Clear all memories
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Learned Memories", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Everything Jarvis remembers about you — preferences, facts, and summaries.",
                    color = TextMuted, fontSize = 12.sp
                )
                if (confirmClearMemories) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { confirmClearMemories = false },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("Cancel", color = TextMuted, fontSize = 13.sp) }
                        TextButton(
                            onClick = {
                                vm.clearAllMemories()
                                confirmClearMemories = false
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("Clear", color = Color(0xFFFF5252), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                } else {
                    OutlinedButton(
                        onClick = { confirmClearMemories = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252))
                    ) { Text("Clear All Memories") }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Clear conversation history
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Conversation History", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Raw dialogue logs — does not remove learned preferences or profile facts.",
                    color = TextMuted, fontSize = 12.sp
                )
                if (confirmClearHistory) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { confirmClearHistory = false },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("Cancel", color = TextMuted, fontSize = 13.sp) }
                        TextButton(
                            onClick = {
                                vm.clearConversationHistory()
                                confirmClearHistory = false
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Text("Clear", color = Color(0xFFFF5252), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                } else {
                    OutlinedButton(
                        onClick = { confirmClearHistory = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252))
                    ) { Text("Clear Conversation History") }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

// ── OpenAI OAuth section ──────────────────────────────────────────────────────

@Composable
private fun OpenAiOAuthSection(
    clientId: String,
    signedIn: Boolean,
    errorMessage: String?,
    onClientIdChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (signedIn) {
            // ── Connected state ────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D2A1A), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Connected to OpenAI",
                    color = Color(0xFF00E676),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Jarvis is using your OpenAI account via OAuth.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252))
            ) {
                Text("Sign out")
            }
        } else {
            // ── Sign-in state ──────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1B2A), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Sign in with your OpenAI account",
                    color = Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Register an OAuth app at platform.openai.com to get a client ID.\n" +
                    "Set redirect URI to: com.jarvis.assistant://oauth/callback",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
            OutlinedTextField(
                value = clientId,
                onValueChange = onClientIdChange,
                label = { Text("OpenAI Client ID", color = TextMuted) },
                placeholder = { Text("app_…", color = TextMuted) },
                colors = jarvisTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (errorMessage != null) {
                Text(errorMessage, color = Color(0xFFFF5252), fontSize = 12.sp)
            }
            Button(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A1A2E),
                    contentColor = Cyan
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Cyan),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Text("Sign in with OpenAI", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SpeakerProfileRow(
    person  : PersonRecord,
    onDelete: () -> Unit,
    onTrain : () -> Unit
) {
    var trainScheduled by remember { mutableStateOf(false) }
    val statusLabel = when (person.typedEnrollmentStatus) {
        PersonRecord.EnrollmentStatus.NONE       -> "No voice samples"
        PersonRecord.EnrollmentStatus.TRAINING   -> "Training (${person.enrolledUtteranceCount} samples)"
        PersonRecord.EnrollmentStatus.SUFFICIENT -> "Sufficient (${person.enrolledUtteranceCount} samples)"
        PersonRecord.EnrollmentStatus.ENROLLED   -> "Enrolled (${person.enrolledUtteranceCount} samples)"
    }
    val statusColor = when (person.typedEnrollmentStatus) {
        PersonRecord.EnrollmentStatus.ENROLLED   -> Color(0xFF00E676)
        PersonRecord.EnrollmentStatus.SUFFICIENT -> Color(0xFFFFD600)
        PersonRecord.EnrollmentStatus.TRAINING   -> Color(0xFFFF9800)
        PersonRecord.EnrollmentStatus.NONE       -> TextMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            tint = if (person.isOwner) Cyan else TextMuted,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(person.displayName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (person.isOwner) {
                    Text(
                        "Owner",
                        color = Cyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(Cyan.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Text(statusLabel, color = statusColor, fontSize = 11.sp)
        }
        var confirmingDelete by remember { mutableStateOf(false) }
        if (confirmingDelete) {
            TextButton(
                onClick = { confirmingDelete = false },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("Cancel", color = TextMuted, fontSize = 12.sp)
            }
            TextButton(
                onClick = { onDelete(); confirmingDelete = false },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) {
                Text("Delete", color = Color(0xFFFF5252), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            // Train voice button — schedules enrollment for the next Jarvis session.
            IconButton(
                onClick = { onTrain(); trainScheduled = true },
                modifier = Modifier.size(32.dp),
                enabled = !trainScheduled
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Train voice for ${person.displayName}",
                    tint = if (trainScheduled) Cyan else TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick  = { confirmingDelete = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${person.displayName}",
                    tint  = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (trainScheduled) {
            Text(
                "Activate Jarvis to start",
                color = Cyan,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = Cyan,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun jarvisTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Cyan,
    unfocusedBorderColor = Color(0xFF333355),
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = Cyan,
    focusedContainerColor = Surface,
    unfocusedContainerColor = Surface
)
