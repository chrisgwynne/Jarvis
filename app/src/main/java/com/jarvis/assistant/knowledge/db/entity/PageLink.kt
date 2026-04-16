package com.jarvis.assistant.knowledge.db.entity

import androidx.room.Entity

@Entity(
    tableName = "page_links",
    primaryKeys = ["fromPageId", "toPageId", "linkType"]
)
data class PageLink(
    val fromPageId: Long,
    val toPageId: Long,
    val linkType: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MENTIONS    = "mentions"
        const val RELATED_TO  = "related_to"
        const val CHILD_OF    = "child_of"
        const val FOLLOWS     = "follows"
    }
}
