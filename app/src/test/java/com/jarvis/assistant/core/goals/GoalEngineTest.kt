package com.jarvis.assistant.core.goals

import com.jarvis.assistant.core.situations.Situation
import com.jarvis.assistant.core.situations.SituationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalEngineTest {

    private val engine = GoalEngine()
    private val nowMs = 1_714_000_000_000L

    private fun sit(type: SituationType) = Situation(
        type = type,
        confidence = 0.8f, urgency = 0.8f,
        createdAtMs = nowMs, expiresAtMs = nowMs + 60_000L,
        evidence = listOf("ev"), sourceSignals = listOf("src"), summary = "s",
    )

    @Test
    fun `leaving home situation produces GET_READY_TO_LEAVE intent`() {
        val intents = engine.derive(listOf(sit(SituationType.LEAVING_HOME_SOON)))
        assertEquals(1, intents.size)
        assertEquals(GoalType.GET_READY_TO_LEAVE, intents[0].type)
        assertEquals(SituationType.LEAVING_HOME_SOON, intents[0].originSituation)
    }

    @Test
    fun `meeting situation produces PREPARE_FOR_MEETING`() {
        val intents = engine.derive(listOf(sit(SituationType.IN_MEETING_AND_UNAVAILABLE)))
        assertEquals(GoalType.PREPARE_FOR_MEETING, intents[0].type)
    }

    @Test
    fun `missed call situation produces RETURN_MISSED_CALL`() {
        val intents = engine.derive(listOf(sit(SituationType.MISSED_CALL_WHILE_NOW_FREE)))
        assertEquals(GoalType.RETURN_MISSED_CALL, intents[0].type)
    }

    @Test
    fun `night situation produces WIND_DOWN_FOR_NIGHT`() {
        val intents = engine.derive(listOf(sit(SituationType.TIRED_LATE_NIGHT_INTERACTION)))
        assertEquals(GoalType.WIND_DOWN_FOR_NIGHT, intents[0].type)
    }

    @Test
    fun `low battery before travel folds into GET_READY_TO_LEAVE`() {
        val intents = engine.derive(listOf(sit(SituationType.LOW_BATTERY_BEFORE_TRAVEL)))
        assertEquals(GoalType.GET_READY_TO_LEAVE, intents[0].type)
    }

    @Test
    fun `informative-only situations produce no intent`() {
        val intents = engine.derive(listOf(
            sit(SituationType.POSSIBLE_DELIVERY_WAITING),
            sit(SituationType.REPEATED_PATTERN_MOMENT),
        ))
        assertTrue(intents.isEmpty())
    }

    @Test
    fun `rootDedupeKey is stable across same day situations`() {
        val intents = engine.derive(listOf(
            sit(SituationType.LEAVING_HOME_SOON),
            sit(SituationType.LEAVING_HOME_SOON),
        ))
        assertEquals(intents[0].rootDedupeKey, intents[1].rootDedupeKey)
    }

    @Test
    fun `evidence is propagated`() {
        val intents = engine.derive(listOf(sit(SituationType.MISSED_CALL_WHILE_NOW_FREE)))
        assertEquals(listOf("ev"), intents[0].evidence)
    }
}
