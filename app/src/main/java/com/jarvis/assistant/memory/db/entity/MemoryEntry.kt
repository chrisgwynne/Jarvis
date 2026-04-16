package com.jarvis.assistant.memory.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * MemoryEntry — one unit of long-term memory.
 *
 * TYPES:
 *   EPISODIC   — summary of a specific past conversation
 *   PREFERENCE — something the user explicitly prefers ("I like concise answers")
 *   CONTACT    — recurring contact info beyond the phone book
 *   TASK       — an unresolved item the user mentioned
 *   ROUTINE    — a recurring action or pattern Jarvis noticed
 *   FACTUAL    — a user-stated fact ("I work at Acme Corp")
 *   SUMMARY    — automated session summary written by MemoryWriter
 *
 * RETRIEVAL:
 *   keywords is a comma-separated list of lowercase tokens for fast FTS-lite matching.
 *   importanceScore [0.0 – 1.0] biases retrieval ranking.
 */
@Entity(
    tableName = "memory_entries",
    indices = [Index("type"), Index("createdAt"), Index("lastAccessedAt")]
)
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: MemoryType,
    val content: String,
    val keywords: String,            // comma-separated lowercase tokens
    val sessionId: String? = null,   // which conversation session produced this
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val importanceScore: Float = 0.5f
)

enum class MemoryType {
    EPISODIC, PREFERENCE, CONTACT, TASK, ROUTINE, FACTUAL, SUMMARY
}
