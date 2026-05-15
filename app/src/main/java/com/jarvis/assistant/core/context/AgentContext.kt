package com.jarvis.assistant.core.context

import com.jarvis.assistant.context.ActivityMode
import com.jarvis.assistant.context.DeviceContext
import com.jarvis.assistant.context.Presence
import com.jarvis.assistant.context.TimePhase
import com.jarvis.assistant.location.LocationTransition
import com.jarvis.assistant.proactive.ContextSnapshot

/**
 * AgentContext — one unified point-in-time view of everything the agent
 * knows about the device, user presence, and live proactive state.
 *
 * Supersedes the old trio of [DeviceContext] + [Presence] + [ContextSnapshot]
 * by carrying all three as structured slices. Call sites that only want a
 * slice read the matching field; call sites that want the whole picture read
 * the whole object. Nothing downstream has to re-compute what another layer
 * already knows.
 *
 * Built by [AgentContextProvider]. Immutable snapshot — safe to share across
 * coroutines and cache briefly.
 */
data class AgentContext(
    val nowMs: Long,
    val device: DeviceContext,
    val presence: Presence,
    val proactive: ContextSnapshot,
    val ambient: com.jarvis.assistant.ambient.AmbientContext =
        com.jarvis.assistant.ambient.AmbientContext.EMPTY,
) {
    val timePhase: TimePhase get() = presence.timePhase
    val activity: ActivityMode get() = presence.activity
    val batteryPercent: Int get() = device.batteryPercent
    val isCharging: Boolean get() = device.isCharging
    val isOnline: Boolean get() = device.isOnline
    val isDriving: Boolean get() = proactive.isDriving
    val isJarvisSpeaking: Boolean get() = proactive.isJarvisSpeaking
    val isJarvisListening: Boolean get() = proactive.isJarvisListening
    val minutesSinceUserInteraction: Long get() = presence.minutesSinceInteraction
    val locationName: String? get() = device.location ?: proactive.currentLocationName
    val locationTransition: LocationTransition? get() = proactive.lastLocationTransition
    val headsetConnected: Boolean get() = device.headsetConnected

    fun allowsSoftSuggestions(): Boolean = presence.allowsSoftSuggestions()
}
