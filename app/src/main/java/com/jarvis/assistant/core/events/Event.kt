package com.jarvis.assistant.core.events

import java.util.concurrent.atomic.AtomicLong

/**
 * Event — the normalized envelope every sensed signal flows in on [EventBus].
 *
 * All payload fields go through [payload] so consumers do not have to know the
 * producer's types. Keys are snake_case by convention; common keys:
 *
 *   app_package, text, title, contact_name, phone_number, battery_pct,
 *   charging, place, ssid, device_name, bt_class, confidence.
 *
 * [sensitivity] drives the [com.jarvis.assistant.core.safety.Sanitizer]:
 * PUBLIC flows through unchanged, PERSONAL gets PII redaction, SECRET is
 * dropped from prompt context entirely.
 */
data class Event(
    val id: Long,
    val kind: EventKind,
    val source: String,
    val tsMillis: Long,
    val actor: String? = null,
    val payload: Map<String, String> = emptyMap(),
    val confidence: Float = 1f,
    val sensitivity: Sensitivity = Sensitivity.PERSONAL,
    val dedupeKey: String? = null,
) {
    enum class Sensitivity { PUBLIC, PERSONAL, SECRET }

    companion object {
        private val SEQ = AtomicLong(1)

        fun of(
            kind: EventKind,
            source: String,
            payload: Map<String, String> = emptyMap(),
            actor: String? = null,
            confidence: Float = 1f,
            sensitivity: Sensitivity = Sensitivity.PERSONAL,
            dedupeKey: String? = null,
            tsMillis: Long = System.currentTimeMillis(),
        ): Event = Event(
            id = SEQ.getAndIncrement(),
            kind = kind,
            source = source,
            tsMillis = tsMillis,
            actor = actor,
            payload = payload,
            confidence = confidence,
            sensitivity = sensitivity,
            dedupeKey = dedupeKey,
        )
    }
}
