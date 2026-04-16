package com.jarvis.assistant.memory.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jarvis.assistant.memory.db.entity.ConversationSession
import com.jarvis.assistant.memory.db.entity.ConversationTurn

@Dao
interface ConversationDao {

    // ── Sessions ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ConversationSession)

    @Update
    suspend fun updateSession(session: ConversationSession)

    @Query("SELECT * FROM conversation_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int): List<ConversationSession>

    @Query("SELECT * FROM conversation_sessions WHERE id = :id")
    suspend fun getSession(id: String): ConversationSession?

    // ── Turns ─────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ConversationTurn): Long

    @Query("SELECT * FROM conversation_turns WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getTurnsForSession(sessionId: String): List<ConversationTurn>

    @Query("SELECT * FROM conversation_turns ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTurns(limit: Int): List<ConversationTurn>

    /** Delete all turns from sessions older than [before]. */
    @Query("""
        DELETE FROM conversation_turns
        WHERE sessionId IN (
            SELECT id FROM conversation_sessions WHERE startedAt < :before
        )
    """)
    suspend fun pruneTurnsOlderThan(before: Long)

    @Query("DELETE FROM conversation_sessions WHERE startedAt < :before")
    suspend fun pruneSessionsOlderThan(before: Long)

    @Query("DELETE FROM conversation_turns")
    suspend fun deleteAllTurns()

    @Query("DELETE FROM conversation_sessions")
    suspend fun deleteAllSessions()
}
