package com.jarvis.assistant.memory.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One complete conversation session (wake → silence/stop). */
@Entity(tableName = "conversation_sessions")
data class ConversationSession(
    @PrimaryKey val id: String,           // UUID
    val startedAt: Long,
    val endedAt: Long? = null,
    val turnCount: Int = 0,
    val summary: String? = null           // written by MemorySummarizer on close
)
