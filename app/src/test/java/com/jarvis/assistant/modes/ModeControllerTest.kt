package com.jarvis.assistant.modes

import com.jarvis.assistant.context.ActivityMode
import com.jarvis.assistant.context.AmbientContextSnapshot
import com.jarvis.assistant.context.Presence
import com.jarvis.assistant.context.TimePhase
import com.jarvis.assistant.voice.VoiceFeatureFlags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tier-B unit tests for [ModeController.consumeContext] auto-switching.
 *
 * Flag-gated by [VoiceFeatureFlags.Flag.JARVIS_MODES_ENABLED]; tests flip
 * the flag on/off explicitly to exercise both paths.
 */
class ModeControllerTest {

    private lateinit var controller: ModeController

    private fun snapshot(
        driving:  Boolean = false,
        screenOn: Boolean = true,
        headset:  Boolean = false,
        phase:    TimePhase = TimePhase.DAY,
    ) = AmbientContextSnapshot(
        timestampMs            = 0L,
        isDriving              = driving,
        isInCall               = false,
        isJarvisSpeaking       = false,
        isJarvisListening      = false,
        batteryPercent         = null,
        isCharging             = false,
        screenOn               = screenOn,
        isHeadsetConnected     = headset,
        isMediaPlaying         = false,
        foregroundAppPackage   = null,
        isOnline               = true,
        presence               = Presence(
            timePhase                 = phase,
            activity                  = ActivityMode.IDLE,
            minutesSinceInteraction   = 0L
        ),
        msSinceLastInteraction = 0L
    )

    @Before fun setUp() {
        VoiceFeatureFlags.setOverride(VoiceFeatureFlags.Flag.JARVIS_MODES_ENABLED, true)
        controller = ModeController()
    }

    @After fun tearDown() {
        VoiceFeatureFlags.clearOverride(VoiceFeatureFlags.Flag.JARVIS_MODES_ENABLED)
    }

    @Test fun `default mode is NORMAL`() {
        assertEquals(JarvisMode.NORMAL, controller.current)
    }

    @Test fun `driving snapshot auto-switches to DRIVING`() {
        controller.consumeContext(snapshot(driving = true))
        assertEquals(JarvisMode.DRIVING, controller.current)
    }

    @Test fun `night plus screen off auto-switches to NIGHT`() {
        controller.consumeContext(snapshot(phase = TimePhase.NIGHT, screenOn = false))
        assertEquals(JarvisMode.NIGHT, controller.current)
    }

    @Test fun `night with screen on stays NORMAL`() {
        controller.consumeContext(snapshot(phase = TimePhase.NIGHT, screenOn = true))
        assertEquals(JarvisMode.NORMAL, controller.current)
    }

    @Test fun `driving wins over night`() {
        controller.consumeContext(snapshot(driving = true, phase = TimePhase.NIGHT, screenOn = false))
        assertEquals(JarvisMode.DRIVING, controller.current)
    }

    @Test fun `manual override locks against auto-switch`() {
        controller.set(JarvisMode.WORK, reason = "manual")
        controller.consumeContext(snapshot(driving = true))
        assertEquals("Manual lock must beat auto-switch",
            JarvisMode.WORK, controller.current)
        controller.releaseLock()
        controller.consumeContext(snapshot(driving = true))
        assertEquals(JarvisMode.DRIVING, controller.current)
    }

    @Test fun `flag-off returns NORMAL regardless of stored value`() {
        controller.consumeContext(snapshot(driving = true))
        VoiceFeatureFlags.setOverride(VoiceFeatureFlags.Flag.JARVIS_MODES_ENABLED, false)
        assertEquals(JarvisMode.NORMAL, controller.current)
    }

    @Test fun `back-to-normal when context clears`() {
        controller.consumeContext(snapshot(driving = true))
        assertEquals(JarvisMode.DRIVING, controller.current)
        controller.consumeContext(snapshot(driving = false))
        assertEquals(JarvisMode.NORMAL, controller.current)
    }
}
