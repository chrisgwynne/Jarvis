package com.jarvis.assistant.voice

/**
 * VoiceFeatureFlags — single registry for every flag that gates the
 * accuracy / routing / barge-in / Whisper upgrade work.
 *
 * Each flag defaults to the **current** production behaviour so that
 * merging a half-wired feature can never regress users until the flag
 * is flipped in [overrides] (or, later, the Settings UI).
 *
 * The runtime reads flags through [isEnabled] so we can switch the
 * backing store later (SharedPreferences, RemoteConfig, A/B test bucket)
 * without touching every call-site.
 */
object VoiceFeatureFlags {

    enum class Flag(val key: String, val defaultEnabled: Boolean) {
        // ── Voice pipeline speed ────────────────────────────────────────────
        FAST_VOICE_PIPELINE_ENABLED        ("voice.fast_pipeline",            true),

        // ── Group A — routing ───────────────────────────────────────────────
        LOCAL_FIRST_ROUTING_ENABLED        ("voice.local_first_routing",      true),
        VOICE_GRAMMAR_ROUTER_ENABLED       ("voice.grammar_router",           false),
        // Alias kept for the canonical name in the spec; same semantics as VOICE_GRAMMAR_ROUTER_ENABLED.
        COMMAND_GRAMMAR_ROUTER_ENABLED     ("voice.command_grammar_router",   false),

        // ── Group B — accuracy ──────────────────────────────────────────────
        VOICE_VOCABULARY_BIAS_ENABLED      ("voice.vocabulary_bias",          true),
        VOCABULARY_BIAS_ENABLED            ("voice.vocab_bias",               true),  // spec alias
        VOICE_ALIAS_LEARNING_ENABLED       ("voice.alias_learning",           true),
        ALIAS_LEARNING_ENABLED             ("voice.alias_learn",              true),  // spec alias
        VOICE_CONFIDENCE_CONFIRMATION_ENABLED("voice.confidence_confirmation", false),

        // ── Group C — barge-in ──────────────────────────────────────────────
        VOICE_BARGE_IN_ENABLED             ("voice.barge_in",                 true),
        BARGE_IN_ENABLED                   ("voice.barge_in_canonical",       true),  // spec alias

        // ── Group D — STT / TTS backend ─────────────────────────────────────
        VOICE_STREAMING_STT_ENABLED        ("voice.streaming_stt",            false),
        REMOTE_WHISPER_STT_ENABLED         ("voice.remote_whisper_stt",       false),
        STREAMING_TTS_ENABLED              ("voice.streaming_tts",            false),

        // ── Higher-level systems (Phase 7+) ─────────────────────────────────
        EXECUTIVE_CONTROLLER_ENABLED       ("voice.executive_controller",     false),
        MEMORY_GRAPH_ENABLED               ("voice.memory_graph",             false),
        PROACTIVE_ENGINE_ENABLED           ("voice.proactive_engine",         true),
        AMBIENT_CONTEXT_ENABLED            ("voice.ambient_context",          false),
        NOTIFICATION_INTELLIGENCE_ENABLED  ("voice.notification_intelligence",true),
        JARVIS_MODES_ENABLED               ("voice.modes",                    false),
        OPENCLAW_AGENT_ROUTING_ENABLED     ("voice.openclaw_agent_routing",   false),

        // ── Always-listening attention gate ─────────────────────────────────
        // When ON, every transcript is scored by AttentionGate before routing.
        // When OFF, every transcript is accepted (legacy behaviour).
        ATTENTION_GATE_ENABLED             ("voice.attention_gate",           true),

        // ── Adaptive wake-word threshold (Tier C3) ──────────────────────────
        // When ON, the wake-word detectors maintain a rolling ambient-RMS
        // estimate and raise their score threshold when background noise is
        // high.  When OFF, the fixed compile-time threshold is used.
        ADAPTIVE_WAKE_THRESHOLD_ENABLED    ("voice.adaptive_wake_threshold",  false),

        // ── WhatsApp auto-send ──────────────────────────────────────────────
        // When ON and the JarvisAccessibilityService is connected, the Send
        // button is tapped automatically after the WhatsApp compose screen
        // opens.  When OFF (or when the accessibility service is not
        // connected), the message is prefilled and the user taps Send.
        WHATSAPP_AUTO_SEND_ENABLED         ("voice.whatsapp_auto_send",       true),

        // ── Messaging-app notification announce ─────────────────────────────
        // When ON, inbound notifications from messaging apps (WhatsApp,
        // Signal, Telegram, system Messages, Messenger, etc. — see
        // MessagingAppClassifier) are elevated to InterruptLevel.ACTIVE so
        // they are spoken aloud by TTS instead of merely existing in the
        // shade.  Default OFF — first-time users should opt in deliberately
        // via the Experimental Flags screen to avoid surprise audio.
        MESSAGING_NOTIFICATION_ANNOUNCE_ENABLED
            ("voice.messaging_notification_announce",                          false);
    }

    /**
     * In-memory overrides applied on top of [Flag.defaultEnabled].
     * Tests and Settings screens write here; nothing else should.
     */
    private val overrides: MutableMap<String, Boolean> = mutableMapOf()

    fun isEnabled(flag: Flag): Boolean =
        overrides[flag.key] ?: flag.defaultEnabled

    fun setOverride(flag: Flag, enabled: Boolean) {
        overrides[flag.key] = enabled
    }

    fun clearOverride(flag: Flag) {
        overrides.remove(flag.key)
    }

    /** For diagnostics / status line — never log secrets. */
    fun snapshot(): Map<String, Boolean> =
        Flag.values().associate { it.key to isEnabled(it) }
}
