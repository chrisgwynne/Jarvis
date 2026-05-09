package com.jarvis.assistant.runtime

/**
 * Random spoken acknowledgements played immediately after the wake word is detected.
 * Keeps the interaction feeling alive rather than silently starting to listen.
 */
internal object WakeAcknowledgements {

    private val PHRASES = listOf(
        "Yeah?",
        "Go ahead.",
        "Listening.",
        "I'm here.",
        "Talk to me.",
        "Ready.",
        "Yep.",
        "You rang?",
        "Go.",
        "Sup?",
        "Right here.",
        "Hey Chris.",
        "What's up?",
        "What do you need?",
        "What's good, Chris?",
        "Hey, what's up?",
        "Hey — how can I help?",
        "What can I do for you?",
        "Yeah, I'm listening.",
        "What's happening?",
        "On it — go.",
        "Chris — go.",
        "You good? What do you need?",
        "Go for it.",
        "Hey motherfucker, what do you need?",
        "What's up, Chris?",
        "Hit me.",
        "Yeah, go ahead.",
        "What's the move?",
        "I got you — what's up?",
    )

    fun random(): String = PHRASES.random()
}
