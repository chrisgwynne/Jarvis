package com.jarvis.assistant.security

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StoragePolicy].
 *
 * Coverage:
 *  - [StoragePolicy.isSafeImageUrl]: pure-logic URL scheme validation (no Context needed)
 *      - HTTPS is allowed
 *      - HTTP is blocked
 *      - file:// URIs are blocked
 *      - data: URIs are blocked
 *      - Blank / empty strings are blocked
 *  - [StoragePolicy.isValidImageReadUri]: URI scheme validation
 *      - content:// URIs are accepted
 *      - file:// raw storage paths are blocked
 *
 * NOTE: [StoragePolicy.isApprovedImageWriteUri] and [StoragePolicy.isAppOwnedPath] require a
 * real or Robolectric-backed [android.content.Context] to resolve filesDir / cacheDir paths.
 * Robolectric is not currently in the test dependencies (see app/build.gradle.kts).
 * Those tests are marked as TODO stubs below — add the Robolectric dependency
 * ("org.robolectric:robolectric:<version>") to testImplementation and annotate the class with
 * @RunWith(RobolectricTestRunner::class) to enable them.
 *
 * For [isValidImageReadUri] tests that don't inspect app-owned paths, a Mockito-mocked Context
 * is sufficient because only the URI scheme is inspected.  Mockito-kotlin IS available.
 */
class StoragePolicyTest {

    // ── isSafeImageUrl — pure-logic, no Context required ─────────────────────

    @Test
    fun `isSafeImageUrl — HTTPS allowed`() {
        assertTrue(StoragePolicy.isSafeImageUrl("https://example.com/image.jpg"))
    }

    @Test
    fun `isSafeImageUrl — HTTPS with path and query params allowed`() {
        assertTrue(StoragePolicy.isSafeImageUrl("https://cdn.example.com/img/photo.png?v=2"))
    }

    @Test
    fun `isSafeImageUrl — HTTP blocked`() {
        assertFalse(StoragePolicy.isSafeImageUrl("http://example.com/image.jpg"))
    }

    @Test
    fun `isSafeImageUrl — HTTP with subpath blocked`() {
        assertFalse(StoragePolicy.isSafeImageUrl("http://192.168.1.1/capture.jpg"))
    }

    @Test
    fun `isSafeImageUrl — file scheme blocked`() {
        assertFalse(StoragePolicy.isSafeImageUrl("file:///sdcard/image.jpg"))
    }

    @Test
    fun `isSafeImageUrl — file scheme with internal path blocked`() {
        assertFalse(StoragePolicy.isSafeImageUrl("file:///data/data/com.jarvis.assistant/files/secret.jpg"))
    }

    @Test
    fun `isSafeImageUrl — data URI blocked`() {
        assertFalse(StoragePolicy.isSafeImageUrl("data:image/png;base64,abc"))
    }

    @Test
    fun `isSafeImageUrl — data URI jpeg blocked`() {
        assertFalse(StoragePolicy.isSafeImageUrl("data:image/jpeg;base64,/9j/4AAQSkZJRgAB"))
    }

    @Test
    fun `isSafeImageUrl — empty string blocked`() {
        assertFalse(StoragePolicy.isSafeImageUrl(""))
    }

    @Test
    fun `isSafeImageUrl — blank whitespace blocked`() {
        assertFalse(StoragePolicy.isSafeImageUrl("   "))
    }

    // ── isValidImageReadUri — scheme-level checks using Uri.parse (no Context) ─

    @Test
    fun `isValidImageReadUri — content URI accepted`() {
        // StoragePolicy.isValidImageReadUri inspects the URI scheme; for content://
        // URIs it does not need to resolve app-owned paths, so a null Context is
        // acceptable IF the implementation short-circuits on scheme.  If the
        // implementation unconditionally calls context methods, use a mock instead.
        //
        // Using Mockito-kotlin mock for safety — it returns sensible defaults and
        // avoids a NullPointerException if the implementation does dereference context.
        val context = org.mockito.kotlin.mock<android.content.Context>()
        val uri = Uri.parse("content://media/external/images/media/123")

        assertTrue(
            "content:// URIs should be accepted as valid image read URIs",
            StoragePolicy.isValidImageReadUri(context, uri)
        )
    }

    @Test
    fun `isValidImageReadUri — file scheme raw storage path blocked`() {
        val context = org.mockito.kotlin.mock<android.content.Context>()
        val uri = Uri.parse("file:///sdcard/DCIM/photo.jpg")

        assertFalse(
            "file:// URIs pointing to raw external storage must be blocked",
            StoragePolicy.isValidImageReadUri(context, uri)
        )
    }

    @Test
    fun `isValidImageReadUri — content URI for downloads accepted`() {
        val context = org.mockito.kotlin.mock<android.content.Context>()
        val uri = Uri.parse("content://downloads/public/1234")

        assertTrue(
            "content:// downloads URIs should be accepted",
            StoragePolicy.isValidImageReadUri(context, uri)
        )
    }

    // ── isApprovedImageWriteUri — TODO: requires Robolectric ─────────────────

    // TODO: Add Robolectric dependency and enable these tests:
    //
    // @RunWith(RobolectricTestRunner::class) on class
    //
    // @Test
    // fun `isApprovedImageWriteUri — media URI accepted`() {
    //     val context = ApplicationProvider.getApplicationContext<Context>()
    //     val uri = Uri.parse("content://media/external/images/media/1")
    //     assertTrue(StoragePolicy.isApprovedImageWriteUri(context, uri))
    // }
    //
    // @Test
    // fun `isApprovedImageWriteUri — app filesDir URI accepted`() {
    //     val context = ApplicationProvider.getApplicationContext<Context>()
    //     val uri = Uri.fromFile(File(context.filesDir, "output.jpg"))
    //     assertTrue(StoragePolicy.isApprovedImageWriteUri(context, uri))
    // }
    //
    // @Test
    // fun `isApprovedImageWriteUri — arbitrary external path rejected`() {
    //     val context = ApplicationProvider.getApplicationContext<Context>()
    //     val uri = Uri.parse("file:///sdcard/Downloads/malicious.jpg")
    //     assertFalse(StoragePolicy.isApprovedImageWriteUri(context, uri))
    // }

    // ── isAppOwnedPath — TODO: requires Robolectric ───────────────────────────

    // TODO: Add Robolectric dependency and enable these tests:
    //
    // @Test
    // fun `isAppOwnedPath — filesDir path is app-owned`() {
    //     val context = ApplicationProvider.getApplicationContext<Context>()
    //     val path = context.filesDir.absolutePath + "/image.jpg"
    //     assertTrue(StoragePolicy.isAppOwnedPath(path, context))
    // }
    //
    // @Test
    // fun `isAppOwnedPath — cacheDir path is app-owned`() {
    //     val context = ApplicationProvider.getApplicationContext<Context>()
    //     val path = context.cacheDir.absolutePath + "/thumb.jpg"
    //     assertTrue(StoragePolicy.isAppOwnedPath(path, context))
    // }
    //
    // @Test
    // fun `isAppOwnedPath — sdcard path is NOT app-owned`() {
    //     val context = ApplicationProvider.getApplicationContext<Context>()
    //     assertFalse(StoragePolicy.isAppOwnedPath("/sdcard/DCIM/photo.jpg", context))
    // }
    //
    // @Test
    // fun `isAppOwnedPath — data data path for different package is NOT app-owned`() {
    //     val context = ApplicationProvider.getApplicationContext<Context>()
    //     assertFalse(StoragePolicy.isAppOwnedPath("/data/data/com.other.app/files/evil.jpg", context))
    // }
}
