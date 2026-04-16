package com.jarvis.assistant.knowledge.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contradictions",
    indices = [
        Index("pageId"),
        Index("status")
    ]
)
data class ContradictionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val oldFactId: Long,
    val newFactId: Long,
    val status: String = UNRESOLVED,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val UNRESOLVED           = "UNRESOLVED"
        const val RESOLVED_NEW_WINS    = "RESOLVED_NEW_WINS"
        const val RESOLVED_OLD_WINS    = "RESOLVED_OLD_WINS"
        const val IGNORED              = "IGNORED"
    }
}
