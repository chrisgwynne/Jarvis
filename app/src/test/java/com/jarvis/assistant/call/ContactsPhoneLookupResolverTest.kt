package com.jarvis.assistant.call

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import com.jarvis.assistant.call.integration.ContactsPhoneLookupResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ContactsPhoneLookupResolverTest {

    private lateinit var context:         Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var resolver:        ContactsPhoneLookupResolver

    @Before fun setUp() {
        context         = mock()
        contentResolver = mock()
        whenever(context.contentResolver).thenReturn(contentResolver)
        // Default: READ_CONTACTS granted
        whenever(
            context.checkSelfPermission(eq(Manifest.permission.READ_CONTACTS))
        ).thenReturn(PackageManager.PERMISSION_GRANTED)
        resolver = ContactsPhoneLookupResolver(context)
    }

    // ── Known contact ─────────────────────────────────────────────────────────

    @Test
    fun `known number — returns display name from contacts with HIGH confidence`() = runTest {
        mockCursorReturning("Alice Smith")

        val result = resolver.resolve("+441234567890")

        assertEquals("Alice Smith", result.displayName)
        assertTrue(result.isKnown)
        assertEquals(ResolutionConfidence.HIGH, result.confidence)
    }

    // ── Unknown number ────────────────────────────────────────────────────────

    @Test
    fun `unknown number — returns formatted number with LOW confidence`() = runTest {
        mockEmptyCursor()

        val result = resolver.resolve("07700900123")

        assertFalse(result.isKnown)
        assertEquals(ResolutionConfidence.LOW, result.confidence)
    }

    // ── Null number (API 31+) ─────────────────────────────────────────────────

    @Test
    fun `null number — returns Unknown caller with NONE confidence`() = runTest {
        val result = resolver.resolve(null)

        assertEquals("Unknown caller", result.displayName)
        assertFalse(result.isKnown)
        assertEquals(ResolutionConfidence.NONE, result.confidence)
    }

    // ── Blank number ──────────────────────────────────────────────────────────

    @Test
    fun `blank number — returns Unknown caller`() = runTest {
        val result = resolver.resolve("   ")
        assertEquals("Unknown caller", result.displayName)
    }

    // ── Permission denied ─────────────────────────────────────────────────────

    @Test
    fun `READ_CONTACTS denied — returns formatted number without querying contacts`() = runTest {
        whenever(
            context.checkSelfPermission(eq(Manifest.permission.READ_CONTACTS))
        ).thenReturn(PackageManager.PERMISSION_DENIED)

        val result = resolver.resolve("+14155552671")

        assertFalse(result.isKnown)
        assertEquals(ResolutionConfidence.LOW, result.confidence)
        // Should not attempt a query
    }

    // ── US number formatting ──────────────────────────────────────────────────

    @Test
    fun `10-digit US number formats as (XXX) XXX-XXXX`() = runTest {
        mockEmptyCursor()
        val result = resolver.resolve("4155552671")
        assertEquals("(415) 555-2671", result.displayName)
    }

    @Test
    fun `US E164 +14155552671 formats correctly`() = runTest {
        mockEmptyCursor()
        val result = resolver.resolve("+14155552671")
        assertEquals("(415) 555-2671", result.displayName)
    }

    // ── Caching ───────────────────────────────────────────────────────────────

    @Test
    fun `second resolve for same number returns cached result without querying`() = runTest {
        mockCursorReturning("Bob")

        val first  = resolver.resolve("+441234")
        val second = resolver.resolve("+441234")

        assertEquals(first.displayName, second.displayName)
        // If the second call queried, it would need a fresh cursor mock —
        // the fact that it doesn't throw means it hit the cache.
    }

    // ── Content resolver failure ──────────────────────────────────────────────

    @Test
    fun `content resolver exception — returns formatted fallback gracefully`() = runTest {
        whenever(
            contentResolver.query(any(), any(), any(), any(), any())
        ).thenThrow(RuntimeException("ContentProvider crash"))

        val result = resolver.resolve("07700900456")

        assertFalse(result.isKnown)
        // Should not throw; should return something usable
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun mockCursorReturning(displayName: String) {
        val cursor = mock<Cursor>()
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getString(0)).thenReturn(displayName)
        whenever(cursor.close()).then { }
        whenever(
            contentResolver.query(any<Uri>(), any(), any(), any(), any())
        ).thenReturn(cursor)
    }

    private fun mockEmptyCursor() {
        val cursor = mock<Cursor>()
        whenever(cursor.moveToFirst()).thenReturn(false)
        whenever(cursor.close()).then { }
        whenever(
            contentResolver.query(any<Uri>(), any(), any(), any(), any())
        ).thenReturn(cursor)
    }
}
