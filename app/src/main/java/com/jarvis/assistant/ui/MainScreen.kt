package com.jarvis.assistant.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.jarvis.assistant.ui.ConversationViewModel
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.ui.orb.OrbViewModel
import com.jarvis.assistant.ui.orb.OrbVisualState
import com.jarvis.assistant.ui.waveform.JarvisWaveform

// ── Palette ───────────────────────────────────────────────────────────────────
private val BgDeep      = Color(0xFF08080F)
private val Surface     = Color(0xFF12121E)
private val Cyan        = Color(0xFF00BCD4)
private val Green       = Color(0xFF00E676)
private val Amber       = Color(0xFFFFAB40)
private val Purple      = Color(0xFFCE93D8)
private val Red         = Color(0xFFFF5252)
private val TextPrimary = Color(0xFFDDDDEE)
private val TextMuted   = Color(0xFF505065)
private val TextSubtle  = Color(0xFF383850)

@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current

    // ── Service state — driven by LocalBroadcast ──────────────────────────────
    var isRunning    by remember { mutableStateOf(false) }
    var serviceState by remember { mutableStateOf("IDLE") }

    DisposableEffect(Unit) {
        isRunning = JarvisService.isRunning(context)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    JarvisService.BROADCAST_SERVICE_STARTED -> isRunning = true
                    JarvisService.BROADCAST_SERVICE_STOPPED -> {
                        isRunning    = false
                        serviceState = "IDLE"
                    }
                    JarvisService.BROADCAST_STATE_CHANGED   ->
                        serviceState = intent.getStringExtra(JarvisService.EXTRA_STATE) ?: return
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(JarvisService.BROADCAST_SERVICE_STARTED)
            addAction(JarvisService.BROADCAST_SERVICE_STOPPED)
            addAction(JarvisService.BROADCAST_STATE_CHANGED)
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)
        onDispose { LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver) }
    }

    // ── Orb / waveform state ──────────────────────────────────────────────────
    val orbViewModel : OrbViewModel = viewModel()
    val visualState  by orbViewModel.visualState.collectAsStateWithLifecycle()
    val amplitude    by orbViewModel.amplitude.collectAsStateWithLifecycle()

    // ── Conversation history ──────────────────────────────────────────────────
    val convViewModel: ConversationViewModel = viewModel()
    val turns by convViewModel.turns.collectAsStateWithLifecycle()
    LaunchedEffect(isRunning) {
        if (isRunning) convViewModel.startPolling() else { convViewModel.stopPolling(); convViewModel.refresh() }
    }

    // ── Derived ───────────────────────────────────────────────────────────────
    val isSilenceable = isRunning && serviceState != "IDLE"
    val statusLabel   = orbStatusLabel(visualState)
    val statusColor   by animateColorAsState(
        targetValue   = orbLabelColor(visualState),
        animationSpec = tween(300),
        label         = "statusColor"
    )

    // ── Root — 3-zone layout ──────────────────────────────────────────────────
    // systemBarsPadding() handles status bar (top) and navigation bar (bottom).
    // Additional spacing is added inside each zone for breathing room.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── Zone 1: Header ────────────────────────────────────────────────────
        Spacer(Modifier.height(20.dp))  // extra top breathing room after status bar

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text          = "JARVIS",
                    color         = Cyan,
                    fontSize      = 20.sp,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 5.sp,
                    fontFamily    = FontFamily.Monospace
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text          = "A.I. ASSISTANT",
                    color         = TextSubtle,
                    fontSize      = 9.sp,
                    letterSpacing = 3.sp,
                    fontFamily    = FontFamily.Monospace
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint               = TextMuted,
                    modifier           = Modifier.size(20.dp)
                )
            }
        }

        // ── Flexible spacer above centre ──────────────────────────────────────
        Spacer(Modifier.weight(1f))

        // ── Zone 2: Waveform + status ─────────────────────────────────────────
        JarvisWaveform(
            visualState = visualState,
            amplitude   = amplitude,
            modifier    = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(horizontal = 0.dp)
        )

        Spacer(Modifier.height(24.dp))  // waveform → status label gap

        Text(
            text          = statusLabel,
            color         = statusColor,
            fontSize      = 11.sp,
            fontWeight    = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            fontFamily    = FontFamily.Monospace,
            textAlign     = TextAlign.Center,
            modifier      = Modifier.padding(horizontal = 32.dp)
        )

        // ── Service-health banner — shown only when the service is at risk
        // of being killed by the OS (battery optimisation not exempted).
        ServiceHealthBanner(isRunning = isRunning)

        // ── Conversation history panel ────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        ConversationPanel(turns = turns)

        // ── Flexible spacer below centre ──────────────────────────────────────
        Spacer(Modifier.weight(1f))

        // ── Zone 3: Inline action buttons ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── START / STOP — primary, weight 0.6 (or 1.0 when silence hidden) ─
            val stopContainerColor = if (isRunning) Color(0xFF1A0A0A) else Surface
            val stopContentColor   = if (isRunning) Red else Cyan
            val stopBorderColor    = if (isRunning) Red.copy(alpha = 0.6f) else Cyan.copy(alpha = 0.5f)
            val stopWeight         = if (isSilenceable) 0.6f else 1f

            Button(
                onClick = {
                    if (isRunning) JarvisService.stop(context) else JarvisService.start(context)
                },
                modifier = Modifier
                    .weight(stopWeight)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = stopContainerColor,
                    contentColor   = stopContentColor
                ),
                shape  = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, stopBorderColor)
            ) {
                Text(
                    text          = if (isRunning) "STOP" else "START",
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontFamily    = FontFamily.Monospace,
                    fontSize      = 13.sp
                )
            }

            // ── SILENCE — secondary, weight 0.4, shown only when silenceable ──
            if (isSilenceable) {
                OutlinedButton(
                    onClick = { JarvisService.silence(context) },
                    modifier = Modifier
                        .weight(0.4f)
                        .height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                    shape  = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = 0.5f))
                ) {
                    Text(
                        text          = "SILENCE",
                        fontWeight    = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        fontFamily    = FontFamily.Monospace,
                        fontSize      = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))  // extra bottom breathing room before nav bar
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun orbStatusLabel(state: OrbVisualState): String = when (state) {
    is OrbVisualState.Dormant       -> "offline"
    is OrbVisualState.WakeListening -> "say  \"jarvis\"..."
    is OrbVisualState.Activating    -> "wake phrase detected"
    is OrbVisualState.Listening     -> "listening"
    is OrbVisualState.Processing    ->
        if (state.toolName != null) "running  ${state.toolName}" else "thinking"
    is OrbVisualState.Speaking      -> "speaking"
    is OrbVisualState.Interrupted   -> "interrupted"
    is OrbVisualState.Silencing     -> "silencing..."
    is OrbVisualState.Degraded      -> "offline mode"
    is OrbVisualState.MicBlocked    -> "microphone unavailable"
}

private fun orbLabelColor(state: OrbVisualState): Color = when (state) {
    is OrbVisualState.WakeListening -> Cyan
    is OrbVisualState.Activating    -> Color(0xFFFFFFFF)
    is OrbVisualState.Listening     -> Green
    is OrbVisualState.Processing    -> Amber
    is OrbVisualState.Speaking      -> Purple
    is OrbVisualState.Interrupted   -> Red
    is OrbVisualState.Silencing     -> Cyan.copy(alpha = 0.5f)
    is OrbVisualState.Degraded      -> Color(0xFF78909C)
    is OrbVisualState.MicBlocked    -> Color(0xFF546E7A)
    else                            -> TextMuted
}
