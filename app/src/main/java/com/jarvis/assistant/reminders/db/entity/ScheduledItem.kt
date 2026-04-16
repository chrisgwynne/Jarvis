package com.jarvis.assistant.reminders.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ScheduledItem — a single reminder or timer managed entirely within Jarvis.
 *
 * Unlike AlarmTool/TimerTool which fire Android AlarmClock intents and hand
 * off to the system clock app, ScheduledItem entries are delivered back into
 * the Jarvis pipeline so they are spoken aloud.
 */
@Entity(
    tableName = "scheduled_items",
    indices = [
        Index("status"),
        Index("triggerAtMs")
    ]
)
data class ScheduledItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,                          // what to say when it fires
    val triggerAtMs: Long,                      // epoch ms when it should fire
    val type: ScheduledItemType,
    val status: ScheduledItemStatus = ScheduledItemStatus.PENDING,
    val deliveryMode: DeliveryMode = DeliveryMode.SPEAK_IF_IDLE,
    val repeatIntervalMs: Long = 0L,            // 0 = one-shot
    val createdAt: Long = System.currentTimeMillis()
)

enum class ScheduledItemType { REMINDER, TIMER }

enum class ScheduledItemStatus { PENDING, DELIVERED, CANCELLED }

enum class DeliveryMode {
    /** Speak aloud when idle; show a notification if the pipeline is busy. */
    SPEAK_IF_IDLE,
    /** Interrupt whatever is happening and speak immediately. */
    ALWAYS_SPEAK,
    /** Never speak — always show a notification regardless of state. */
    NOTIFY_ONLY
}
