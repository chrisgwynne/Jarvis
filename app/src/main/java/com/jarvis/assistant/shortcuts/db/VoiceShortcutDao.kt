package com.jarvis.assistant.shortcuts.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VoiceShortcutDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shortcut: VoiceShortcut): Long

    @Query("SELECT * FROM voice_shortcuts ORDER BY name ASC")
    suspend fun getAll(): List<VoiceShortcut>

    @Query("SELECT * FROM voice_shortcuts WHERE triggerNormalized = :trigger LIMIT 1")
    suspend fun findByTrigger(trigger: String): VoiceShortcut?

    @Query("DELETE FROM voice_shortcuts WHERE triggerNormalized = :trigger")
    suspend fun deleteByTrigger(trigger: String): Int

    @Query("DELETE FROM voice_shortcuts")
    suspend fun deleteAll()
}
