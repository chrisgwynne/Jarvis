package com.jarvis.assistant.ui.settings.screens

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.wearables.meta.MetaWearablesState
import com.jarvis.assistant.ui.settings.SettingsActionRow
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsToggleRow
import com.jarvis.assistant.ui.settings.SettingsValueRow
import kotlinx.coroutines.launch

/**
 * Wearables settings — Meta AI glasses module.
 *
 * Master switch is OFF by default.  When the user enables the module,
 * the screen falls into one of:
 *   - `SDK_UNAVAILABLE`  — DAT SDK not on classpath; suggest mock mode
 *   - `DISCONNECTED`     — ready to connect
 *   - `CONNECTING` / `CONNECTED` / `CAMERA_READY` / `STREAMING`
 *   - `PERMISSION_MISSING` / `ERROR` — friendly recovery prompts
 *
 * Diagnostics buttons exercise the live manager so the user can
 * verify the path end-to-end before relying on it for "look at this".
 */
@Composable
internal fun WearablesSettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repo    = JarvisApp.wearablesSettings
    val mgr     = JarvisApp.metaWearables
    val state   by repo.stateFlow.collectAsState()
    val devState by mgr.stateFlow.collectAsState(initial = mgr.currentState)
    val scope = rememberCoroutineScope()
    var lastPhoto by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.enabled, state.useMockDevice) {
        // Whenever the master toggle or mock-device flag flips, ask
        // the manager to re-pick a backend.
        mgr.refresh()
    }

    SettingsScaffold(title = "Wearables", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Meta AI glasses",
            body  = "Optional eyes-and-hands-free module powered by the " +
                    "Meta Wearables DAT SDK.  When enabled, Jarvis can " +
                    "capture from the glasses for \"look at this\", " +
                    "\"take a glasses photo\", and \"read this\".  " +
                    "OFF by default — privacy first.",
        )

        SettingsGroup(title = "Main control", description = "Master switch + mock") {
            SettingsToggleRow(
                title           = "Meta Wearables enabled",
                description     = "Master switch.  OFF keeps the module dormant.",
                checked         = state.enabled,
                onCheckedChange = repo::setEnabled,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Use mock device",
                description     = "Synthesise glasses connection + capture for testing " +
                    "without hardware.  Useful when the DAT SDK isn't " +
                    "installed yet.",
                checked         = state.useMockDevice,
                onCheckedChange = repo::setUseMockDevice,
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "Connection state",
                value       = devState.name,
                description = "Live state from the wearables manager.",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "Registration status",
                value       = mgr.registrationStatusLabel.ifBlank { "—" },
                description = "Whether Jarvis is approved via the Meta AI " +
                    "companion app to use the glasses.",
            )
            SettingsRowDivider()
            SettingsValueRow(
                title       = "Visible devices",
                value       = mgr.visibleDeviceCount.toString(),
                description = "Glasses paired in Meta AI that the SDK can see. " +
                    "Zero means the glasses aren't paired or aren't on.",
            )
            mgr.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                SettingsRowDivider()
                SettingsValueRow(
                    title       = "Last error",
                    value       = err.take(64),
                    description = "Most recent failure from the SDK (logged " +
                        "with [META_WEARABLES_ERROR] in logcat).",
                )
            }
        }

        SettingsGroup(
            title = "App registration",
            description = "Required before the SDK can find your glasses",
        ) {
            SettingsActionRow(
                title       = "Register with Meta AI",
                description = "Opens the Meta AI companion app so you can " +
                    "approve Jarvis as a registered glasses app.  " +
                    "Required once before the first connect.",
                actionLabel = "Register",
                onAction    = {
                    val activity = (context as? android.app.Activity)
                    if (activity == null) {
                        Toast.makeText(context,
                            "Open Settings from the main Jarvis screen so " +
                                "we have an Activity to launch from.",
                            Toast.LENGTH_LONG).show()
                    } else if (!mgr.startRegistration(activity)) {
                        Toast.makeText(context,
                            "Registration unavailable — DAT SDK not active. " +
                                "Check master toggle + mock toggle.",
                            Toast.LENGTH_LONG).show()
                    }
                },
            )
        }

        SettingsGroup(title = "Behaviour", description = "How Jarvis uses the glasses") {
            SettingsToggleRow(
                title           = "Auto-connect on Jarvis start",
                description     = "Attempt a connection during runtime startup.",
                checked         = state.autoConnectOnStart,
                onCheckedChange = repo::setAutoConnectOnStart,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Use glasses for \"look at this\"",
                description     = "Prefer the glasses camera for visual context requests.",
                checked         = state.useForLookAtThis,
                onCheckedChange = repo::setUseForLookAtThis,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Prefer glasses camera",
                description     = "When both phone and glasses can capture, choose glasses.",
                checked         = state.preferGlassesCamera,
                onCheckedChange = repo::setPreferGlassesCamera,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Save captures to gallery",
                description     = "Write glasses photos to the system gallery.",
                checked         = state.saveCapturesToGallery,
                onCheckedChange = repo::setSaveCapturesToGallery,
            )
        }

        SettingsGroup(title = "Vision analysis", description = "OCR / object tags") {
            SettingsToggleRow(
                title           = "Vision analysis enabled",
                description     = "Allow on-device + (optionally) cloud vision passes.",
                checked         = state.visionAnalysisEnabled,
                onCheckedChange = repo::setVisionAnalysisEnabled,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Prefer on-device vision",
                description     = "Use Android ML Kit / Text Recognition over cloud.",
                checked         = state.preferOnDeviceVision,
                onCheckedChange = repo::setPreferOnDeviceVision,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Allow cloud vision",
                description     = "Fall back to multimodal LLM when on-device can't.",
                checked         = state.allowCloudVision,
                onCheckedChange = repo::setAllowCloudVision,
            )
        }

        SettingsGroup(title = "Privacy", description = "Local-first defaults") {
            SettingsToggleRow(
                title           = "Save visual history",
                description     = "Keep a local store of what Jarvis has seen.",
                checked         = state.saveVisualHistory,
                onCheckedChange = repo::setSaveVisualHistory,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Confirm before sharing captures",
                description     = "Ask before sending a captured photo to anyone.",
                checked         = state.confirmBeforeSharing,
                onCheckedChange = repo::setConfirmBeforeSharing,
            )
            SettingsRowDivider()
            SettingsToggleRow(
                title           = "Confirm before saving memory",
                description     = "Ask before creating a visual memory.",
                checked         = state.confirmBeforeSavingMemory,
                onCheckedChange = repo::setConfirmBeforeSavingMemory,
            )
        }

        SettingsGroup(title = "Diagnostics", description = "Test the path end-to-end") {
            SettingsActionRow(
                title       = "Connect glasses",
                description = "Attempt a connection now.",
                actionLabel = "Connect",
                onAction    = {
                    scope.launch {
                        val ok = mgr.connect(withCamera = true)
                        Toast.makeText(context,
                            if (ok) "Connected." else "Couldn't connect (state=${mgr.currentState}).",
                            Toast.LENGTH_SHORT).show()
                    }
                },
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Disconnect",
                description = "Drop the active session.",
                actionLabel = "Disconnect",
                onAction    = {
                    scope.launch { mgr.disconnect(); }
                },
            )
            SettingsRowDivider()
            SettingsActionRow(
                title       = "Capture test photo",
                description = "Capture one frame from the active backend.",
                actionLabel = "Capture",
                onAction    = {
                    scope.launch {
                        val uri = mgr.capturePhoto()
                        lastPhoto = uri
                        Toast.makeText(context,
                            if (uri != null) "Photo captured." else "Capture failed (state=${mgr.currentState}).",
                            Toast.LENGTH_SHORT).show()
                    }
                },
            )
            if (lastPhoto != null) {
                SettingsRowDivider()
                SettingsValueRow(
                    title       = "Last photo",
                    value       = lastPhoto!!.takeLast(48),
                    description = "URI returned by the last capture.",
                )
            }
        }

        SettingsGroup(title = "Reset", description = "Restore defaults") {
            SettingsActionRow(
                title       = "Reset wearables settings",
                description = "Restores the defaults from this screen.",
                actionLabel = "Reset",
                destructive = true,
                confirm     = true,
                onAction    = repo::resetToDefaults,
            )
        }
    }
}
