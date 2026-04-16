package com.jarvis.assistant.memory

import com.jarvis.assistant.memory.db.dao.MemoryDao
import com.jarvis.assistant.memory.db.entity.MemoryEntry
import com.jarvis.assistant.memory.db.entity.MemoryType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MemoryRetrieverTest {

    private lateinit var dao: MemoryDao
    private lateinit var retriever: MemoryRetriever

    private fun entry(
        id: Long,
        content: String,
        keywords: String,
        ageMs: Long = 0L,
        importance: Float = 0.5f,
        accessCount: Int = 0
    ) = MemoryEntry(
        id             = id,
        type           = MemoryType.EPISODIC,
        content        = content,
        keywords       = keywords,
        createdAt      = System.currentTimeMillis() - ageMs,
        lastAccessedAt = System.currentTimeMillis(),
        accessCount    = accessCount,
        importanceScore = importance
    )

    @Before fun setUp() {
        dao = mock()
        retriever = MemoryRetriever(dao)
    }

    @Test fun `recent high-importance memory ranks above old low-importance one`() = runTest {
        val recent = entry(1, "User prefers concise answers", "prefer,concise,answers", ageMs = 0, importance = 0.9f)
        val old    = entry(2, "Something from last month", "something,month", ageMs = 30L * 24 * 3600 * 1000, importance = 0.1f)

        whenever(dao.getRecent(any())).thenReturn(listOf(recent, old))
        whenever(dao.searchByKeyword(any(), any())).thenReturn(emptyList())
        whenever(dao.recordAccess(any(), any())).thenReturn(Unit)

        val results = retriever.retrieveRelevant("user preference", limit = 2)
        assertEquals(recent.id, results.first().id)
    }

    @Test fun `keyword match boosts ranking`() = runTest {
        val weatherEntry = entry(1, "User asked about weather in London", "weather,london,asked")
        val unrelated    = entry(2, "User likes jazz music", "jazz,music,likes")

        whenever(dao.getRecent(any())).thenReturn(listOf(weatherEntry, unrelated))
        whenever(dao.searchByKeyword(any(), any())).thenReturn(emptyList())
        whenever(dao.recordAccess(any(), any())).thenReturn(Unit)

        val results = retriever.retrieveRelevant("what is the weather today")
        assertEquals(weatherEntry.id, results.first().id)
    }

    @Test fun `empty query returns recent entries`() = runTest {
        val entries = listOf(entry(1, "Content A", "a,b,c"))

        whenever(dao.getRecent(any())).thenReturn(entries)
        whenever(dao.recordAccess(any(), any())).thenReturn(Unit)

        val results = retriever.retrieveRelevant("", limit = 5)
        assertTrue(results.isNotEmpty())
    }

    @Test fun `result count respects limit`() = runTest {
        val entries = (1..10).map { entry(it.toLong(), "Entry $it", "entry,test") }

        whenever(dao.getRecent(any())).thenReturn(entries)
        whenever(dao.searchByKeyword(any(), any())).thenReturn(emptyList())
        whenever(dao.recordAccess(any(), any())).thenReturn(Unit)

        val results = retriever.retrieveRelevant("test query", limit = 3)
        assertTrue(results.size <= 3)
    }
}
