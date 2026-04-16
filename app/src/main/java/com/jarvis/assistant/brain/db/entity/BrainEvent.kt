package com.jarvis.assistant.brain.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * BrainEvent — one raw observation logged by [BrainEventCollector].
 *
 * Every event carries a full context snapshot so pattern detection can
 * correlate behaviour with time, location, battery state, etc. without
 * needing to join against other tables.
 *
 * Rolling retention: 60 days raw, then pruned by [BrainEventDao.pruneOlderThan].
 */
@Entity(
    tableName = "brain_events",
    indices = [
        Index("timestamp"),
        Index("type"),
        Index("hourOfDay"),
        Index("dayOfWeek")
    ]
)
data class BrainEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** [BrainEventType] name — stored as String so schema doesn't break on enum changes. */
    val type: String,

    /** Unix epoch milliseconds when the event occurred. */
    val timestamp: Long,

    /** Local hour of day (0–23) at event time. */
    val hourOfDay: Int,

    /** Local minute of hour (0–59) at event time. */
    val minuteOfHour: Int,

    /** ISO day of week: 1=Monday … 7=Sunday. */
    val dayOfWeek: Int,

    /** True if dayOfWeek is Saturday (6) or Sunday (7). */
    val isWeekend: Boolean,

    /** Coarse location state: "home" / "away" / "unknown". */
    val locationState: String,

    /** Battery level 0–100 at event time. */
    val batteryPct: Int,

    /** True if device was charging at event time. */
    val isCharging: Boolean,

    /** True if screen was on at event time. */
    val screenOn: Boolean,

    /** Bluetooth device name for BLUETOOTH_* events; null otherwise. */
    val bluetoothDevice: String? = null,

    /** Package name for APP_OPEN / APP_CLOSE events; null otherwise. */
    val packageName: String? = null,

    /** Any extra payload (e.g. message length, media app name). */
    val extra: String? = null
)
