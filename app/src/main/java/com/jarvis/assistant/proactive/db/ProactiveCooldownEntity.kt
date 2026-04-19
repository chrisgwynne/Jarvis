package com.jarvis.assistant.proactive.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent record of when a proactive surfacing with [dedupeKey] was last
 * delivered, plus how many times the user has ignored it.  Backs
 * [com.jarvis.assistant.proactive.CooldownStore] so adaptive cooldowns survive
 * process death.
 *
 * A reserved key — [GLOBAL_KEY] — records the global last-surface timestamp
 * used for the cross-event cooldown gate in [DecisionEngine].  It shares the
 * same row shape so we can round-trip the whole store with a single DAO.
 */
@Entity(tableName = "proactive_cooldowns")
data class ProactiveCooldownEntity(
    @PrimaryKey val dedupeKey: String,
    val lastSurfacedMs: Long,
    val ignoreCount: Int
) {
    companion object {
        /** Reserved sentinel key for the global last-surface timestamp. */
        const val GLOBAL_KEY = "__global__"
    }
}
