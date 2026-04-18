package com.jarvis.assistant.ui.settings.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.settings.SettingsGroup
import com.jarvis.assistant.ui.settings.SettingsInfoCard
import com.jarvis.assistant.ui.settings.SettingsRowDivider
import com.jarvis.assistant.ui.settings.SettingsScaffold
import com.jarvis.assistant.ui.settings.SettingsTheme

/**
 * "What can Jarvis do?" reference — grouped by capability area.
 *
 * Every entry is pulled from the actual registered tools and intent
 * classifier patterns in the codebase. Example phrases come from the
 * code comments / registered patterns where possible.
 */
@Composable
internal fun FaqSettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    SettingsScaffold(title = "FAQ & Commands", onBack = onBack, onClose = onClose) {

        SettingsInfoCard(
            title = "Speak naturally",
            body  = "You don't have to memorise exact phrases. Jarvis reads intent — " +
                    "the examples below are a guide, not a script. Say things the way " +
                    "you'd say them to a person.",
        )

        FaqSection(title = "Calls & messaging") {
            FaqEntry(
                what = "Make a phone call",
                examples = listOf("call Mum", "phone Chris", "dial Sarah", "ring my dad"),
            )
            FaqEntry(
                what = "End a call",
                examples = listOf("hang up", "end the call", "cancel call"),
            )
            FaqEntry(
                what = "Send an SMS",
                examples = listOf(
                    "text Sarah saying hello",
                    "message Mum I'm on the way",
                    "send a text to Chris",
                ),
            )
            FaqEntry(
                what = "Send a WhatsApp message",
                examples = listOf(
                    "WhatsApp Mum and tell her I'm here",
                    "wa Chris saying nice work",
                ),
            )
        }

        FaqSection(title = "Reminders, timers & alarms") {
            FaqEntry(
                what = "Set a reminder",
                examples = listOf(
                    "remind me in 30 minutes to check the oven",
                    "remind me at 3pm to call the office",
                    "set a reminder for tomorrow at 2pm",
                ),
            )
            FaqEntry(
                what = "Set a timer",
                examples = listOf(
                    "set a timer for 10 minutes",
                    "start a 1 hour timer",
                    "countdown 30 seconds",
                ),
            )
            FaqEntry(
                what = "Set an alarm",
                examples = listOf(
                    "set an alarm for 7am",
                    "add an alarm for 6:30",
                ),
            )
            FaqEntry(
                what = "See what's scheduled",
                examples = listOf(
                    "what reminders do I have",
                    "show my alarms",
                    "list my scheduled items",
                ),
            )
            FaqEntry(
                what = "Cancel something",
                examples = listOf(
                    "cancel that reminder",
                    "remove my 3pm alarm",
                    "clear all timers",
                ),
            )
        }

        FaqSection(title = "Camera & photos") {
            FaqEntry(
                what = "Take a photo",
                examples = listOf(
                    "take a photo",
                    "snap a picture",
                    "take a quick shot",
                ),
            )
            FaqEntry(
                what = "Take a selfie",
                examples = listOf("take a selfie", "snap a selfie"),
            )
            FaqEntry(
                what = "Describe what the camera sees",
                note = "Requires OpenAI or Anthropic provider.",
                examples = listOf(
                    "what do you see",
                    "describe what's in front of you",
                    "analyse this photo",
                ),
            )
        }

        FaqSection(title = "Audio recording") {
            FaqEntry(
                what = "Start recording",
                examples = listOf(
                    "start recording",
                    "begin a voice note",
                    "record the conversation",
                ),
            )
            FaqEntry(
                what = "Stop recording",
                examples = listOf("stop recording", "finish recording"),
            )
            FaqEntry(
                what = "Transcribe the last recording",
                note = "Requires OpenAI (uses Whisper).",
                examples = listOf(
                    "transcribe the last recording",
                    "convert the recording to text",
                ),
            )
            FaqEntry(
                what = "Summarise a recording",
                examples = listOf(
                    "summarise the recording",
                    "recap the last recording",
                ),
            )
        }

        FaqSection(title = "Calendar & notifications") {
            FaqEntry(
                what = "Check your calendar",
                examples = listOf(
                    "what's today",
                    "what's my schedule",
                    "tomorrow's events",
                    "next appointment",
                ),
            )
            FaqEntry(
                what = "Read notifications",
                note = "Needs notification access (System Settings → Notification access).",
                examples = listOf(
                    "read my notifications",
                    "any new messages",
                    "check WhatsApp",
                ),
            )
        }

        FaqSection(title = "Media & device") {
            FaqEntry(
                what = "Control playback",
                examples = listOf("pause", "play", "skip", "next song", "previous"),
            )
            FaqEntry(
                what = "Adjust volume",
                examples = listOf(
                    "turn up the volume",
                    "lower the volume",
                    "mute the phone",
                ),
            )
            FaqEntry(
                what = "Flashlight",
                examples = listOf(
                    "turn on the flashlight",
                    "torch off",
                ),
            )
            FaqEntry(
                what = "Open an app",
                examples = listOf(
                    "open Spotify",
                    "launch Gmail",
                    "start YouTube",
                ),
            )
        }

        FaqSection(title = "Search & info") {
            FaqEntry(
                what = "Search the web",
                note = "Uses DuckDuckGo by default; add a Brave key for richer results.",
                examples = listOf(
                    "what's the weather",
                    "latest news",
                    "bitcoin price",
                    "coffee shops nearby",
                ),
            )
            FaqEntry(
                what = "Daily briefing",
                examples = listOf(
                    "good morning",
                    "what's my day",
                    "briefing",
                ),
            )
        }

        FaqSection(title = "Memory") {
            FaqEntry(
                what = "Tell Jarvis something to remember",
                note = "Captured automatically — just mention it in conversation.",
                examples = listOf(
                    "my name is Chris",
                    "I live in London",
                    "I'm allergic to peanuts",
                    "I prefer black coffee",
                ),
            )
            FaqEntry(
                what = "Recall past conversations",
                examples = listOf(
                    "what did I ask earlier",
                    "do you remember when I said…",
                    "what did you tell me yesterday",
                ),
            )
            FaqEntry(
                what = "See what Jarvis knows",
                examples = listOf(
                    "what do you know about me",
                    "list your memories",
                    "what have you stored",
                ),
            )
        }

        FaqSection(title = "Shopping list") {
            FaqEntry(
                what = "Add items",
                examples = listOf(
                    "add milk to my list",
                    "I need bread",
                    "buy eggs",
                ),
            )
            FaqEntry(
                what = "Remove items",
                examples = listOf(
                    "remove butter from my list",
                    "I got milk",
                ),
            )
            FaqEntry(
                what = "Review the list",
                examples = listOf("what's on my list", "read my shopping list"),
            )
            FaqEntry(
                what = "Clear the list",
                examples = listOf("clear my shopping list"),
            )
        }

        FaqSection(title = "Image generation") {
            FaqEntry(
                what = "Generate an image",
                note = "Only available when the LLM provider is MiniMax.",
                examples = listOf(
                    "generate an image of a sunset",
                    "create a picture of a dog",
                    "draw a forest at night",
                ),
            )
        }

        FaqSection(title = "Help") {
            FaqEntry(
                what = "Ask Jarvis what it can do",
                examples = listOf(
                    "what can you do",
                    "help",
                    "list your features",
                ),
            )
        }

        SettingsInfoCard(
            title = "Permissions matter",
            body  = "Calls, SMS, contacts, camera, recording and notifications all need " +
                    "their Android permission granted first. Jarvis asks on first launch; " +
                    "you can re-check them in Privacy → App permissions.",
        )
    }
}

/* ──────────────────────────────────────────────────────────────────────── */

@Composable
private fun FaqSection(title: String, content: @Composable () -> Unit) {
    SettingsGroup(title = title) {
        // Invoke content in a Column to get consistent spacing between entries.
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun FaqEntry(
    what: String,
    examples: List<String>,
    note: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = what,
            color = SettingsTheme.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (!note.isNullOrBlank()) {
            Text(
                text = note,
                color = SettingsTheme.TextFaint,
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
            )
        }
        examples.forEach { ex ->
            Text(
                text = "\u201C$ex\u201D",
                color = SettingsTheme.Cyan,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .background(
                        color = SettingsTheme.Cyan.copy(alpha = 0.08f),
                        shape = SettingsTheme.ChipShape,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    SettingsRowDivider()
}
