package com.jarvis.assistant.shopping

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shopping_items")
data class ShoppingItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val item: String,
    val addedAt: Long = System.currentTimeMillis(),
    val completed: Boolean = false
)
