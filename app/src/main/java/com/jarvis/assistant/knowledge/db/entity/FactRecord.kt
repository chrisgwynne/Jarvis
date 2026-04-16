package com.jarvis.assistant.knowledge.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "fact_records",
    indices = [
        Index("pageId"),
        Index("predicate"),
        Index("supersededByFactId")
    ]
)
data class FactRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val confidence: Float = 0.8f,
    val sourceId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val supersededByFactId: Long? = null
)
