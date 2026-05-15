package com.jarvis.assistant.preferences.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ResponsePreferenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ResponsePreferenceEntity): Long

    @Query("SELECT * FROM response_preferences ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ResponsePreferenceEntity>

    @Query("SELECT * FROM response_preferences WHERE domain = :domain AND enabled = 1 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActiveByDomain(domain: String): ResponsePreferenceEntity?

    @Query("SELECT * FROM response_preferences WHERE domain = :domain ORDER BY updatedAt DESC")
    suspend fun getAllByDomain(domain: String): List<ResponsePreferenceEntity>

    @Query("DELETE FROM response_preferences WHERE domain = :domain")
    suspend fun deleteByDomain(domain: String)

    @Query("DELETE FROM response_preferences")
    suspend fun deleteAll()

    @Query("UPDATE response_preferences SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE response_preferences SET confidence = :confidence, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConfidence(id: Long, confidence: Float, updatedAt: Long = System.currentTimeMillis())
}
