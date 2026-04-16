package com.jarvis.assistant.knowledge.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jarvis.assistant.knowledge.db.entity.PageLink

@Dao
interface PageLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: PageLink)

    @Query("SELECT * FROM page_links WHERE fromPageId = :fromPageId")
    suspend fun getLinksFrom(fromPageId: Long): List<PageLink>

    @Query("SELECT * FROM page_links WHERE toPageId = :toPageId")
    suspend fun getLinksTo(toPageId: Long): List<PageLink>

    @Query("DELETE FROM page_links WHERE fromPageId = :pageId OR toPageId = :pageId")
    suspend fun deleteLinksFor(pageId: Long)
}
