package com.jarvis.assistant.core.context

import com.jarvis.assistant.context.AudioRoute
import com.jarvis.assistant.context.DeviceContext
import com.jarvis.assistant.context.Presence
import com.jarvis.assistant.proactive.ContextSnapshot

/**
 * AgentContextFactory — build a minimal [AgentContext] from just a
 * [ContextSnapshot]. Used by transitional call sites (EventGenerator,
 * ProactiveSimulator) that have a snapshot but not yet the device/presence
 * slices. Triggers currently read only from [AgentContext.proactive], so
 * the synthesised slices are placeholders. When the full migration lands,
 * these call sites will pass a real [AgentContext] from
 * [AgentContextProvider.current].
 */
object AgentContextFactory {
    fun fromSnapshot(snapshot: ContextSnapshot): AgentContext {
        val nowMs = snapshot.currentTimeMillis
        val device = DeviceContext(
            timestamp = nowMs,
            date = "",
            time = "",
            timezone = "",
            batteryPercent = snapshot.batteryLevel,
            isCharging = snapshot.isCharging,
            deviceModel = "",
            isOnline = snapshot.networkAvailable,
            audioRoute = AudioRoute.UNKNOWN,
            headsetConnected = false,
            location = snapshot.currentLocationName,
        )
        val presence = Presence.compute(
            nowMs = nowMs,
            lastInteractionMs = snapshot.lastUserInteractionTimeMillis,
            isJarvisSpeaking = snapshot.isJarvisSpeaking,
            isJarvisListening = snapshot.isJarvisListening,
            isDriving = snapshot.isDriving,
        )
        return AgentContext(nowMs, device, presence, snapshot)
    }
}
