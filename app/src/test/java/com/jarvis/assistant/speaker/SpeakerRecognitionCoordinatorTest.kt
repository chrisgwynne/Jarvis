package com.jarvis.assistant.speaker

import com.jarvis.assistant.speaker.db.PersonRecordDao
import com.jarvis.assistant.speaker.db.SpeakerEmbeddingDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Tests for the pure-logic parts of [SpeakerRecognitionCoordinator]:
 * specifically [SpeakerRecognitionCoordinator.parseIntroductionName].
 *
 * Speaker identification and enrollment require a running DB + AudioRecord,
 * so those paths are covered by instrumented tests instead.
 */
class SpeakerRecognitionCoordinatorTest {

    private val coordinator: SpeakerRecognitionCoordinator by lazy {
        val store      = SpeakerProfileStore(mock<PersonRecordDao>(), mock<SpeakerEmbeddingDao>())
        val enrollment = SpeakerEnrollmentManager(store)
        SpeakerRecognitionCoordinator(store, enrollment)
    }

    // ── parseIntroductionName — "I'm / It's / This is / My name is / Call me" ──

    @Test fun `parses I'm Chris`() {
        assertEquals("Chris", coordinator.parseIntroductionName("I'm Chris"))
    }

    @Test fun `parses im Chris without apostrophe`() {
        assertEquals("Chris", coordinator.parseIntroductionName("im Chris"))
    }

    @Test fun `parses It's Sarah`() {
        assertEquals("Sarah", coordinator.parseIntroductionName("It's Sarah"))
    }

    @Test fun `parses This is Dave`() {
        assertEquals("Dave", coordinator.parseIntroductionName("This is Dave"))
    }

    @Test fun `parses My name is Alice`() {
        assertEquals("Alice", coordinator.parseIntroductionName("My name is Alice"))
    }

    @Test fun `parses Call me Sam`() {
        assertEquals("Sam", coordinator.parseIntroductionName("Call me Sam"))
    }

    @Test fun `parses bare single word name`() {
        assertEquals("Chris", coordinator.parseIntroductionName("Chris"))
    }

    @Test fun `capitalises first letter`() {
        val name = coordinator.parseIntroductionName("i'm chris")
        assertEquals("Chris", name)
    }

    @Test fun `returns null for empty string`() {
        assertNull(coordinator.parseIntroductionName(""))
    }

    @Test fun `returns null for multi-word bare reply without pattern`() {
        // "hello there" is two words, matches no pattern → null
        assertNull(coordinator.parseIntroductionName("hello there"))
    }

    @Test fun `returns null for single letter below minimum length`() {
        assertNull(coordinator.parseIntroductionName("A"))
    }

    @Test fun `parses two-char name at minimum boundary`() {
        // "Jo" is 2 chars — exactly the minimum for bare single-word match
        assertNotNull(coordinator.parseIntroductionName("Jo"))
    }

    @Test fun `name is capped at 24 chars for bare single-word match`() {
        // 25 chars — exceeds bare-word regex max, should return null
        assertNull(coordinator.parseIntroductionName("A".repeat(25)))
    }
}
