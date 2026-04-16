package com.jarvis.assistant.memory.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * MemoryFact — structured profile fact about the user.
 *
 * Unlike MemoryEntry (episodic/semantic blobs), each MemoryFact has a
 * well-known key so it can be overwritten in place rather than appended.
 *
 * Keys follow a dotted namespace:
 *   user.name      — user's preferred name
 *   user.location  — city/country the user lives in
 *   pref.<millis>  — an explicit preference statement
 *   fact.<slug>    — a user-stated fact
 */
@Entity(
    tableName = "memory_facts",
    indices = [
        Index(value = ["factKey"], unique = true),
        Index("category")
    ]
)
data class MemoryFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val factKey: String,
    val value: String,
    val category: FactCategory,
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

enum class FactCategory {
    NAME,        // user.name
    PREFERENCE,  // explicit user preference ("I prefer concise answers")
    ROUTINE,     // habitual pattern Jarvis detected
    FACT         // any other stated fact
}
