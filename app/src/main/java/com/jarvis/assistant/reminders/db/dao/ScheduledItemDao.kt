package com.jarvis.assistant.reminders.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.jarvis.assistant.reminders.db.entity.ScheduledItem
import com.jarvis.assistant.reminders.db.entity.ScheduledItemStatus

@Dao
interface ScheduledItemDao {

    @Insert
    suspend fun insert(item: ScheduledItem): Long

    @Query("SELECT * FROM scheduled_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ScheduledItem?

    @Query("SELECT * FROM scheduled_items WHERE status = 'PENDING' ORDER BY triggerAtMs ASC")
    suspend fun getPending(): List<ScheduledItem>

    @Query("SELECT * FROM scheduled_items WHERE status = 'PENDING' AND triggerAtMs <= :nowMs ORDER BY triggerAtMs ASC")
    suspend fun getDue(nowMs: Long): List<ScheduledItem>

    @Query("UPDATE scheduled_items SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ScheduledItemStatus)

    @Query("DELETE FROM scheduled_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Remove delivered items older than [beforeMs] to keep the table small. */
    @Query("DELETE FROM scheduled_items WHERE status = 'DELIVERED' AND createdAt < :beforeMs")
    suspend fun pruneDelivered(beforeMs: Long)
}
