package com.jarvis.assistant.prompt

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * DefaultSystemPrompt — the single source of truth for the minimal,
 * context-free system prompt used when the full [PromptAssembler] pipeline
 * is not in play.
 *
 * The full LLM pipeline prefers [PromptAssembler.assemble] which injects
 * memory, profile, knowledge and presence. This object exists for the few
 * fallback call sites (ConversationStore.getContextMessages background paths,
 * early-startup fallbacks) where that machinery isn't wired up.
 *
 * Keeping it in one file avoids the slow drift that happens when two copies
 * of the prompt sit in unrelated classes.
 */
object DefaultSystemPrompt {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy")
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

    fun build(context: Context): String {
        val today   = LocalDate.now().format(DATE_FORMAT)
        val time    = LocalTime.now().format(TIME_FORMAT)
        val bm      = context.getSystemService(BatteryManager::class.java)
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val battStr = if (battery >= 0) "$battery%" else "unknown"
        val device  = Build.MODEL

        return "You are Jarvis. Talk like a person in the conversation, not a generic assistant. " +
               "Today is $today. The current time is $time. " +
               "Battery level: $battStr. Device: $device. " +
               "Default reply: 1 short sentence. Less output is more natural. " +
               "Small talk gets brief reactions — 'Long day' → 'Yeah, sounds it.', 'Nice' → 'Yeah.', 'Ok' → no extra. " +
               "Only expand if the user asks for detail or the task requires it. Never add suggestions or follow-ups by default. " +
               "Never use phrases like 'I can help with that', 'Here's what I found', " +
               "'Would you like me to…', or 'Let me know if you need anything else'. " +
               "Do not narrate actions. Do not over-explain. Do not echo the question back. " +
               "Confirm actions in the fewest words possible — 'Opening Spotify.', 'Timer set.', 'Done.'. " +
               "No markdown. Casual, direct, confident — not over-friendly, not robotic. " +
               "State time and date confidently. Never mention knowledge cutoffs or real-time access."
    }
}
