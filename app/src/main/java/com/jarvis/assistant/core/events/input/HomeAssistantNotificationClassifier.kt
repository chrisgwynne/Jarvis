package com.jarvis.assistant.core.events.input

/**
 * HomeAssistantNotificationClassifier — single source of truth for
 * "is this notification a Home Assistant alert?".
 *
 * Used by:
 *  - [com.jarvis.assistant.notifications.JarvisNotificationListener]
 *    to tag the event and exclude it from the proactive ring buffer.
 *  - [com.jarvis.assistant.proactive.AppNotificationSource]
 *    as a belt-and-braces filter when reading the buffer.
 *  - [com.jarvis.assistant.proactive.TtsProactiveDispatcher]
 *    as the final safety net: refuse to speak anything classified as
 *    a HA alert, regardless of how it arrived.
 *
 * Detection signals (any-match):
 *  1. Package name is a known HA companion app.
 *  2. Notification text contains a motion / camera / doorbell phrase.
 *     (Catches notifications routed via Tasker / generic forwarders that
 *     don't post under the HA package.)
 *
 * Conservative on purpose: false positives just mean the user sees the
 * Android notification but Jarvis stays quiet — which is exactly the
 * behaviour the user requested for this category.
 */
object HomeAssistantNotificationClassifier {

    /** Known HA companion package names across regular + beta channels. */
    private val HA_PACKAGES: Set<String> = setOf(
        "io.homeassistant.companion.android",
        "io.homeassistant.companion.android.beta",
        "io.homeassistant.companion.android.minimal"
    )

    private val MOTION_RX   = Regex("""\bmotion\b""",   RegexOption.IGNORE_CASE)
    private val CAMERA_RX   = Regex("""\bcamera\b|\bcam\b""", RegexOption.IGNORE_CASE)
    private val DOORBELL_RX = Regex("""\b(doorbell|ding[\s-]?dong|ring at the door)\b""", RegexOption.IGNORE_CASE)
    private val AUTOMATION_RX = Regex("""\b(automation|scene|node[\s-]?red|hass)\b""", RegexOption.IGNORE_CASE)

    /** True iff the notification looks like a HA alert by any signal. */
    fun isHomeAssistantAlert(packageName: String, title: String?, text: String?): Boolean {
        if (packageName in HA_PACKAGES) return true
        val haystack = (title.orEmpty() + " " + text.orEmpty())
        return MOTION_RX.containsMatchIn(haystack) ||
            CAMERA_RX.containsMatchIn(haystack) ||
            DOORBELL_RX.containsMatchIn(haystack)
    }

    /** Returns the [JarvisInputEvent.HomeAssistantNotification.Kind] best matching the text. */
    fun classifyKind(title: String?, text: String?): JarvisInputEvent.HomeAssistantNotification.Kind {
        val h = (title.orEmpty() + " " + text.orEmpty())
        return when {
            DOORBELL_RX.containsMatchIn(h)   -> JarvisInputEvent.HomeAssistantNotification.Kind.DOORBELL
            MOTION_RX.containsMatchIn(h)     -> JarvisInputEvent.HomeAssistantNotification.Kind.MOTION
            CAMERA_RX.containsMatchIn(h)     -> JarvisInputEvent.HomeAssistantNotification.Kind.CAMERA
            AUTOMATION_RX.containsMatchIn(h) -> JarvisInputEvent.HomeAssistantNotification.Kind.AUTOMATION
            else                             -> JarvisInputEvent.HomeAssistantNotification.Kind.OTHER
        }
    }

    fun knownPackages(): Set<String> = HA_PACKAGES
}
