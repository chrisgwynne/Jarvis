package com.jarvis.assistant.vision

import com.jarvis.assistant.memory.db.dao.MemoryDao
import com.jarvis.assistant.memory.db.entity.MemoryEntry
import com.jarvis.assistant.memory.db.entity.MemoryType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins the two storage-gate invariants the rest of the "look at this" feature
 * depends on:
 *   * Sensitive screens never reach the memory DAO.
 *   * Sub-threshold confidence screens never reach the memory DAO.
 * A clean screen at or above threshold is persisted with the correct
 * MemoryType so later retrieval queries can find it by type.
 */
class ScreenObservationRepositoryTest {

    private lateinit var dao: MemoryDao
    private lateinit var repo: ScreenObservationRepository

    @Before fun setUp() {
        dao = mock()
        repo = ScreenObservationRepository(dao)
    }

    @Test fun `sensitive screen is never persisted`() = runTest {
        val result = repo.save(
            analysis = analysis(sensitive = true, confidence = 0.99),
            screenshotPath = "/tmp/x.png",
            foregroundPackage = "com.example.bank",
            capturedAtMs = 1_000L
        )
        assertSame(ScreenObservationRepository.SaveResult.SkippedSensitive, result)
        verify(dao, never()).insert(any())
    }

    @Test fun `low-confidence screen is never persisted`() = runTest {
        val result = repo.save(
            analysis = analysis(sensitive = false, confidence = 0.4),
            screenshotPath = "/tmp/x.png",
            foregroundPackage = "com.example",
            capturedAtMs = 1_000L
        )
        assertTrue(result is ScreenObservationRepository.SaveResult.SkippedLowConfidence)
        verify(dao, never()).insert(any())
    }

    @Test fun `clean high-confidence screen is persisted as SCREEN_OBSERVATION`() = runTest {
        whenever(dao.insert(any())).thenReturn(42L)

        val a = analysis(
            sensitive  = false,
            confidence = 0.82,
            appName    = "Gmail",
            screenType = "email_thread",
            importantText = listOf("Invoice due Friday"),
            brands     = listOf("Acme")
        )
        val result = repo.save(
            analysis          = a,
            screenshotPath    = "/data/data/.../42.png",
            foregroundPackage = "com.google.android.gm",
            capturedAtMs      = 2_000L
        )

        assertEquals(ScreenObservationRepository.SaveResult.Saved(42L), result)

        val captor = argumentCaptor<MemoryEntry>()
        verify(dao).insert(captor.capture())
        val saved = captor.firstValue
        assertEquals(MemoryType.SCREEN_OBSERVATION, saved.type)
        assertEquals(2_000L, saved.createdAt)
        // keyword blob must contain the app + at least one salient token so
        // ScreenObservationRetriever.byApp / byKeyword can find it.
        assertTrue("keywords=${saved.keywords}", saved.keywords.contains("gmail"))
        assertTrue("keywords=${saved.keywords}", saved.keywords.contains("acme"))
        // content is JSON — cheap sanity check so round-tripping via
        // ScreenObservation.fromStoredContent stays intact.
        assertTrue(saved.content.trimStart().startsWith("{"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun analysis(
        sensitive: Boolean,
        confidence: Double,
        appName: String = "Example",
        screenType: String = "unknown",
        importantText: List<String> = emptyList(),
        brands: List<String> = emptyList()
    ): VisionScreenAnalyzer.ScreenAnalysis = VisionScreenAnalyzer.ScreenAnalysis(
        summary       = "a screen",
        appName       = appName,
        screenType    = screenType,
        userIntent    = "",
        importantText = importantText,
        actionItems   = emptyList(),
        people        = emptyList(),
        brands        = brands,
        products      = emptyList(),
        urls          = emptyList(),
        emails        = emptyList(),
        sensitive     = sensitive,
        confidence    = confidence,
        rawJson       = """{"summary":"a screen","app_name":"$appName","screen_type":"$screenType",""" +
                        """"user_intent":"","important_text":[],"action_items":[],""" +
                        """"entities":{"people":[],"brands":[],"products":[],"urls":[],"emails":[]},""" +
                        """"sensitive":$sensitive,"confidence":$confidence}"""
    )
}
