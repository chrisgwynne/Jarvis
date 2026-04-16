package com.jarvis.assistant.knowledge.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jarvis.assistant.knowledge.db.entity.WikiPage

@Dao
interface WikiPageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(page: WikiPage): Long

    @Update
    suspend fun update(page: WikiPage)

    @Query("SELECT * FROM wiki_pages WHERE id = :id")
    suspend fun getById(id: Long): WikiPage?

    @Query("SELECT * FROM wiki_pages WHERE titleNormalized = :titleNormalized LIMIT 1")
    suspend fun getByNormalizedTitle(titleNormalized: String): WikiPage?

    @Query("SELECT * FROM wiki_pages WHERE pageType = :pageType ORDER BY updatedAt DESC")
    suspend fun getByType(pageType: String): List<WikiPage>

    @Query("SELECT * FROM wiki_pages WHERE status = 'ACTIVE' ORDER BY updatedAt DESC")
    suspend fun getActive(): List<WikiPage>

    @Query("""
        SELECT * FROM wiki_pages
        WHERE status != 'ARCHIVED'
        AND (title LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%')
        ORDER BY updatedAt DESC
        LIMIT 10
    """)
    suspend fun search(query: String): List<WikiPage>

    @Query("SELECT * FROM wiki_pages WHERE status = 'ACTIVE' AND updatedAt < :cutoffMs")
    suspend fun getStale(cutoffMs: Long): List<WikiPage>

    @Query("SELECT COUNT(*) FROM wiki_pages WHERE pageType = :pageType")
    suspend fun countByType(pageType: String): Int

    @Query("UPDATE wiki_pages SET status = 'ARCHIVED', updatedAt = :archivedAt WHERE id = :id")
    suspend fun archivePage(id: Long, archivedAt: Long)
}
