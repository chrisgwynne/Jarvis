package com.jarvis.assistant.todoist.parse

import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Audit harness — runs the parser against every example listed in the
 * hardening spec and PRINTS the structured result.  We intentionally
 * don't `assert` here; this test is a diagnostic that lights up the
 * current behaviour so the audit notes are objective.  The strict
 * regression assertions live in ReminderIntentParserTest.
 *
 * Run with: ./gradlew :app:testDebugUnitTest --tests "*.ParserAuditTest"
 * — the stdout shows up in the HTML report.
 */
class ParserAuditTest {

    // Pinned clock — Wed 2026-05-13 10:00 UTC.  All "tomorrow", "Friday",
    // etc. resolve relative to this.
    private val now: Long = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        .apply { set(2026, Calendar.MAY, 13, 10, 0, 0); set(Calendar.MILLISECOND, 0) }
        .timeInMillis

    @Test
    fun `audit every spec example`() {
        val cases = listOf(
            "remind me to take bins out tomorrow at 7",
            "remind me about MOT tomorrow",
            "add buy milk to my reminders",
            "todo call Mike p1 tomorrow",
            "don't let me forget to order filament",
            "put printer maintenance on my work list",
            "I need to remember to pay the invoice Friday",
            "I've got to ring the dentist",
            "remind me every Monday to put bins out",
            "remind me in 10 minutes to check the printer",
            "remind me tonight to lock the door",
        )
        for (raw in cases) {
            val m = ReminderIntentParser.parse(raw, now)
            println("---")
            println("INPUT:   $raw")
            if (m == null) {
                println("PARSE:   <null>")
            } else {
                println("KIND:    ${m.kind}")
                println("CONTENT: \"${m.content}\"")
                println("DATE:    ${m.date}")
                println("TIME:    ${m.time}")
                println("RECUR:   ${m.recurrence}")
                println("PRI:     ${m.priority}")
                println("PROJECT: ${m.projectHint}")
                println("LABELS:  ${m.labels}")
                println("CTX:     ${m.contextTrigger}")
                println("REPEAT:  ${m.repeat}")
                println("NEEDS:   ${m.needsTimeFollowUp}")
            }
        }
    }
}
