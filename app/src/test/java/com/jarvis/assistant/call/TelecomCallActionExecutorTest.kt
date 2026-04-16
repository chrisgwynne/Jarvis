package com.jarvis.assistant.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import com.jarvis.assistant.call.integration.TelecomCallActionExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class TelecomCallActionExecutorTest {

    private lateinit var context:        Context
    private lateinit var telecomManager: TelecomManager
    private lateinit var executor:       TelecomCallActionExecutor

    @Before fun setUp() {
        context        = mock()
        telecomManager = mock()
        whenever(context.getSystemService(TelecomManager::class.java)).thenReturn(telecomManager)
        // Default: ANSWER_PHONE_CALLS granted
        whenever(
            context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
        ).thenReturn(PackageManager.PERMISSION_GRANTED)
        executor = TelecomCallActionExecutor(context)
    }

    // ── Answer ────────────────────────────────────────────────────────────────

    @Test
    fun `answer — permission granted — calls acceptRingingCall and returns Success`() = runTest {
        val result = executor.answer()

        verify(telecomManager).acceptRingingCall()
        assertEquals(CallActionResult.Success, result)
    }

    @Test
    fun `answer — permission denied — returns PermissionDenied without calling TelecomManager`() = runTest {
        whenever(
            context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
        ).thenReturn(PackageManager.PERMISSION_DENIED)

        val result = executor.answer()

        assertEquals(CallActionResult.PermissionDenied, result)
    }

    @Test
    fun `answer — SecurityException from TelecomManager — returns PermissionDenied`() = runTest {
        doThrow(SecurityException("test")).whenever(telecomManager).acceptRingingCall()

        val result = executor.answer()

        assertEquals(CallActionResult.PermissionDenied, result)
    }

    @Test
    fun `answer — generic exception — returns Failure`() = runTest {
        doThrow(RuntimeException("bork")).whenever(telecomManager).acceptRingingCall()

        val result = executor.answer()

        assert(result is CallActionResult.Failure)
    }

    // ── Decline ───────────────────────────────────────────────────────────────

    @Test
    @Suppress("DEPRECATION")
    fun `decline — permission granted — calls endCall and returns Success`() = runTest {
        val result = executor.decline()

        verify(telecomManager).endCall()
        assertEquals(CallActionResult.Success, result)
    }

    @Test
    fun `decline — permission denied — returns PermissionDenied`() = runTest {
        whenever(
            context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
        ).thenReturn(PackageManager.PERMISSION_DENIED)

        val result = executor.decline()

        assertEquals(CallActionResult.PermissionDenied, result)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `decline — SecurityException — returns PermissionDenied`() = runTest {
        doThrow(SecurityException("denied")).whenever(telecomManager).endCall()

        val result = executor.decline()

        assertEquals(CallActionResult.PermissionDenied, result)
    }

    // ── TelecomManager unavailable ────────────────────────────────────────────

    @Test
    fun `answer — TelecomManager null — returns NotAvailable`() = runTest {
        whenever(context.getSystemService(TelecomManager::class.java)).thenReturn(null)
        val nullExecutor = TelecomCallActionExecutor(context)

        assertEquals(CallActionResult.NotAvailable, nullExecutor.answer())
    }

    @Test
    fun `decline — TelecomManager null — returns NotAvailable`() = runTest {
        whenever(context.getSystemService(TelecomManager::class.java)).thenReturn(null)
        val nullExecutor = TelecomCallActionExecutor(context)

        assertEquals(CallActionResult.NotAvailable, nullExecutor.decline())
    }
}
