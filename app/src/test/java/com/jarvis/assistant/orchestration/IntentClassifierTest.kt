package com.jarvis.assistant.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentClassifierTest {

    // ── Name storage ──────────────────────────────────────────────────────────

    @Test
    fun `my name is Chris — RememberFact with user name key`() {
        val result = IntentClassifier.classify("My name is Chris")
        assertTrue(result is ConversationAction.RememberFact)
        val action = result as ConversationAction.RememberFact
        assertEquals("user.name", action.key)
        assertEquals("Chris", action.value)
    }

    @Test
    fun `I am Sam — extracts name`() {
        val result = IntentClassifier.classify("I am Sam")
        assertTrue(result is ConversationAction.RememberFact)
        assertEquals("Sam", (result as ConversationAction.RememberFact).value)
    }

    @Test
    fun `call me Dave — extracts name`() {
        val result = IntentClassifier.classify("Call me Dave")
        assertTrue(result is ConversationAction.RememberFact)
        assertEquals("Dave", (result as ConversationAction.RememberFact).value)
    }

    // ── Location storage ──────────────────────────────────────────────────────

    @Test
    fun `I live in London — RememberFact with location key`() {
        val result = IntentClassifier.classify("I live in London")
        assertTrue(result is ConversationAction.RememberFact)
        val action = result as ConversationAction.RememberFact
        assertEquals("user.location", action.key)
        assertEquals("London", action.value)
    }

    // ── Explicit memory store ─────────────────────────────────────────────────

    @Test
    fun `remember that I prefer concise answers — RememberFact preference`() {
        val result = IntentClassifier.classify("remember that I prefer concise answers")
        assertTrue(result is ConversationAction.RememberFact)
        val action = result as ConversationAction.RememberFact
        assertTrue(action.key.startsWith("pref."))
    }

    @Test
    fun `I prefer dark mode — RememberFact preference`() {
        val result = IntentClassifier.classify("I prefer dark mode")
        assertTrue(result is ConversationAction.RememberFact)
    }

    // ── Memory recall ─────────────────────────────────────────────────────────

    @Test
    fun `what's my name — RecallFact`() {
        val result = IntentClassifier.classify("What's my name?")
        assertTrue(result is ConversationAction.RecallFact)
    }

    @Test
    fun `what do you know about me — RecallFact`() {
        val result = IntentClassifier.classify("What do you know about me?")
        assertTrue(result is ConversationAction.RecallFact)
    }

    // ── Timer creation ────────────────────────────────────────────────────────

    @Test
    fun `set a timer for 10 minutes — CreateTimer`() {
        val result = IntentClassifier.classify("set a timer for 10 minutes")
        assertTrue(result is ConversationAction.CreateTimer)
    }

    @Test
    fun `start a countdown for 5 minutes — CreateTimer`() {
        val result = IntentClassifier.classify("start a countdown for 5 minutes")
        assertTrue(result is ConversationAction.CreateTimer)
    }

    // ── Reminder creation ─────────────────────────────────────────────────────

    @Test
    fun `remind me at 3pm to call dentist — CreateReminder`() {
        val result = IntentClassifier.classify("remind me at 3pm to call the dentist")
        assertTrue(result is ConversationAction.CreateReminder)
    }

    @Test
    fun `remind me in 30 minutes — CreateReminder`() {
        val result = IntentClassifier.classify("remind me in 30 minutes")
        assertTrue(result is ConversationAction.CreateReminder)
    }

    @Test
    fun `set a reminder for tomorrow — CreateReminder`() {
        val result = IntentClassifier.classify("set a reminder for tomorrow at 9am")
        assertTrue(result is ConversationAction.CreateReminder)
    }

    // ── List reminders ────────────────────────────────────────────────────────

    @Test
    fun `what reminders do I have — ListReminders`() {
        val result = IntentClassifier.classify("what reminders do I have?")
        assertEquals(ConversationAction.ListReminders, result)
    }

    @Test
    fun `show my timers — ListReminders`() {
        val result = IntentClassifier.classify("show my timers")
        assertEquals(ConversationAction.ListReminders, result)
    }

    // ── Cancel reminder ───────────────────────────────────────────────────────

    @Test
    fun `cancel my reminder — CancelReminder`() {
        val result = IntentClassifier.classify("cancel my reminder")
        assertTrue(result is ConversationAction.CancelReminder)
    }

    @Test
    fun `delete the timer — CancelReminder`() {
        val result = IntentClassifier.classify("delete the timer")
        assertTrue(result is ConversationAction.CancelReminder)
    }

    // ── Pass-through ──────────────────────────────────────────────────────────

    @Test
    fun `what is the weather — PassThrough`() {
        val result = IntentClassifier.classify("What's the weather like today?")
        assertEquals(ConversationAction.PassThrough, result)
    }

    @Test
    fun `play music — PassThrough`() {
        val result = IntentClassifier.classify("play some music")
        assertEquals(ConversationAction.PassThrough, result)
    }

    @Test
    fun `tell me a joke — PassThrough`() {
        val result = IntentClassifier.classify("tell me a joke")
        assertEquals(ConversationAction.PassThrough, result)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `common words not extracted as name — I am not sure passes through`() {
        // "I am not" — "not" is a stop word so name extraction should fail
        val result = IntentClassifier.classify("I am not sure about that")
        // Should NOT return RememberFact("user.name", "not sure about that")
        assertTrue(result !is ConversationAction.RememberFact ||
                   (result as ConversationAction.RememberFact).key != "user.name")
    }
}
