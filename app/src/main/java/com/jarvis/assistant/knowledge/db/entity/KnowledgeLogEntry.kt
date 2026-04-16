package com.jarvis.assistant.knowledge.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_log",
    indices = [Index("createdAt")]
)
data class KnowledgeLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val operationType: String,
    val summary: String,
    val affectedPageIds: String = ""
) {
    companion object {
        const val INGEST  = "INGEST"
        const val COMPILE = "COMPILE"
        const val UPDATE  = "UPDATE"
        const val LINT    = "LINT"
        const val COMPACT = "COMPACT"
        const val QUERY   = "QUERY"
    }
}
