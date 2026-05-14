package com.jarvis.assistant.tools.device.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaContextStoreTest {

    @After
    fun tearDown() { MediaContextStore.clear() }

    @Test
    fun `record then peek returns the same entry`() {
        MediaContextStore.record(
            MediaContextStore.Entry(
                filePath = "/data/user/0/com.jarvis.assistant/cache/camera/abc.jpg",
                mimeType = "image/jpeg",
                kind     = "selfie",
            )
        )
        val e = MediaContextStore.peek()
        assertNotNull(e)
        assertEquals("selfie", e!!.kind)
        assertEquals("image/jpeg", e.mimeType)
    }

    @Test
    fun `last-writer wins`() {
        MediaContextStore.record(
            MediaContextStore.Entry("/a.jpg", "image/jpeg", "photo")
        )
        MediaContextStore.record(
            MediaContextStore.Entry("/b.jpg", "image/jpeg", "selfie")
        )
        assertEquals("selfie", MediaContextStore.peek()!!.kind)
        assertEquals("/b.jpg", MediaContextStore.peek()!!.filePath)
    }

    @Test
    fun `clear empties the slot`() {
        MediaContextStore.record(
            MediaContextStore.Entry("/a.jpg", "image/jpeg", "photo")
        )
        MediaContextStore.clear()
        assertNull(MediaContextStore.peek())
    }
}
