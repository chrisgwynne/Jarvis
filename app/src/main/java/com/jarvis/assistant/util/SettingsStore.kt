package com.jarvis.assistant.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SettingsStore — a thin wrapper around EncryptedSharedPreferences.
 *
 * WHY ENCRYPTED?
 * The API key is a secret credential. Plain SharedPreferences are stored as an
 * XML file that any app with root access (or a backup) could read. AES256-GCM
 * via Android Keystore means the key material never leaves secure hardware on
 * devices that have a TEE (virtually all modern Android phones).
 *
 * The MasterKey lives in the Android Keystore — not in our app storage — so it
 * survives app reinstalls but is wiped on factory reset.
 *
 * THREAD SAFETY: SharedPreferences reads are safe from any thread.
 * Writes commit synchronously (apply() is async, commit() is sync).
 * We use apply() throughout since we don't need write confirmation.
 */
class SettingsStore(context: Context) {

    companion object {
        private const val PREFS_FILE = "jarvis_settings"

        // Keys
        const val KEY_LLM_PROVIDER       = "llm_provider"
        const val KEY_API_KEY            = "api_key"
        const val KEY_OLLAMA_BASE_URL    = "ollama_base_url"
        const val KEY_MINIMAX_BASE_URL   = "minimax_base_url"
        const val KEY_MINIMAX_MODEL      = "minimax_model"
        const val KEY_WAKE_WORD          = "wake_word"
        const val KEY_VOICE_RESPONSE     = "voice_response"
        const val KEY_TTS_VOICE_NAME     = "tts_voice_name"
        const val KEY_BRAVE_SEARCH_KEY       = "brave_search_key"
        const val KEY_GOOGLE_MAPS_KEY        = "google_maps_key"
        const val KEY_DEFAULT_MSG_CHANNEL    = "default_msg_channel"

        // GitHub issue reporting (owner/dev mode).  All off-by-default; see
        // reporting/github/IssueReporter.kt for the full behaviour contract.
        const val KEY_GH_REPORTING_ENABLED = "gh_reporting_enabled"
        const val KEY_GH_REPO_OWNER        = "gh_repo_owner"
        const val KEY_GH_REPO_NAME         = "gh_repo_name"
        const val KEY_GH_TOKEN             = "gh_token"
        const val DEFAULT_GH_REPO_OWNER    = "chrisgwynne"
        const val DEFAULT_GH_REPO_NAME     = "Jarvis"

        // OpenAI OAuth keys
        const val KEY_OPENAI_CLIENT_ID     = "openai_client_id"
        const val KEY_OPENAI_ACCESS_TOKEN  = "openai_access_token"
        const val KEY_OPENAI_REFRESH_TOKEN = "openai_refresh_token"
        const val KEY_OPENAI_CODE_VERIFIER = "openai_code_verifier"
        const val KEY_OPENAI_OAUTH_ENABLED = "openai_oauth_enabled"

        // Voice enrollment trigger (set from Settings UI, consumed at next session start)
        const val KEY_PENDING_ENROLL_PID  = "pending_voice_enrollment_pid"

        // LLM response length
        const val KEY_MAX_TOKENS         = "max_tokens"
        const val DEFAULT_MAX_TOKENS     = 1200

        // Fallback LLM provider (empty = disabled)
        const val KEY_FALLBACK_PROVIDER  = "fallback_provider"

        // Home Assistant
        const val KEY_HA_BASE_URL        = "ha_base_url"
        const val KEY_HA_API_TOKEN       = "ha_api_token"

        // ── Cloud sync (optional, opt-in) ────────────────────────────────────
        // Firebase credentials entered by the user at runtime. With all four
        // populated, CloudSyncService can initialise a FirebaseApp without
        // google-services.json being baked in at build time.
        const val KEY_CLOUD_SYNC_ENABLED   = "cloud_sync_enabled"
        const val KEY_FIREBASE_API_KEY     = "firebase_api_key"
        const val KEY_FIREBASE_APP_ID      = "firebase_app_id"
        const val KEY_FIREBASE_PROJECT_ID  = "firebase_project_id"
        const val KEY_FIREBASE_DB_URL      = "firebase_db_url"
        const val KEY_CLOUD_SYNC_EMAIL     = "cloud_sync_email"
        /** Timestamp of the most recent successful two-way sync, 0 if never. */
        const val KEY_CLOUD_SYNC_LAST_MS   = "cloud_sync_last_ms"

        // ── Safety / lifecycle toggles ────────────────────────────────────────
        /** Kill switch: when true, ActionPolicyGate denies every tool execution. */
        const val KEY_TOOL_EXECUTION_DISABLED = "tool_execution_disabled"
        /** When false, BootReceiver skips auto-starting JarvisService on boot. */
        const val KEY_AUTO_START_ON_BOOT      = "auto_start_on_boot"

        // ── Appearance (Phase 2) ──────────────────────────────────────────────
        /** Theme mode key — values: "system" | "light" | "dark" | "amoled". */
        const val KEY_THEME_MODE       = "theme_mode"
        /** When true and Android 12+, derive scheme from wallpaper. */
        const val KEY_DYNAMIC_COLOR    = "dynamic_color"
        const val DEFAULT_THEME_MODE   = "system"

        // ── App lock (Phase 4b) ───────────────────────────────────────────────
        const val KEY_APP_LOCK_ENABLED    = "app_lock_enabled"
        const val KEY_APP_LOCK_BIOMETRIC  = "app_lock_biometric_enabled"
        const val KEY_APP_LOCK_PIN_HASH   = "app_lock_pin_hash"
        const val KEY_APP_LOCK_PIN_SALT   = "app_lock_pin_salt"
        /** Timestamp (ms) of the last successful unlock; session expires after [APP_LOCK_SESSION_MS]. */
        const val KEY_APP_LOCK_LAST_UNLOCK = "app_lock_last_unlock_ms"
        /** Window during which a successful unlock suppresses further prompts. */
        const val APP_LOCK_SESSION_MS     = 5L * 60_000L   // 5 minutes

        // ── HermesAgent (Phase 6) ─────────────────────────────────────────────
        // Self-hosted LAN/Tailscale Hermes Agent
        // (https://github.com/NousResearch/hermes-agent).  The chat-completions
        // endpoint is OpenAI-compatible so reuse of LlmRouter is the cheap path;
        // the /api/jobs CRUD surface gets its own client (HermesJobsClient).
        const val KEY_HERMES_ENABLED      = "hermes_enabled"
        const val KEY_HERMES_HOST         = "hermes_host"
        const val KEY_HERMES_PORT         = "hermes_port"
        const val KEY_HERMES_SECURE       = "hermes_secure"
        const val KEY_HERMES_API_KEY      = "hermes_api_key"
        const val KEY_HERMES_PROFILE      = "hermes_profile"
        const val KEY_HERMES_TIMEOUT_MS   = "hermes_timeout_ms"
        const val DEFAULT_HERMES_PORT     = 8000
        const val DEFAULT_HERMES_PROFILE  = "hermes-agent"
        const val DEFAULT_HERMES_TIMEOUT  = 30_000L

        // OpenClaw remote routing keys
        const val KEY_OPENCLAW_ENABLED      = "openclaw_enabled"
        const val KEY_OPENCLAW_HOST         = "openclaw_host"
        const val KEY_OPENCLAW_PORT         = "openclaw_port"
        const val KEY_OPENCLAW_SECURE       = "openclaw_secure"
        const val KEY_OPENCLAW_AUTH_TOKEN   = "openclaw_auth_token"
        const val KEY_OPENCLAW_TIMEOUT_MS   = "openclaw_timeout_ms"
        const val KEY_OPENCLAW_MODEL        = "openclaw_model"
        const val KEY_OPENCLAW_KEYWORD      = "openclaw_keyword"
        const val KEY_OPENCLAW_NODE_ENABLED = "openclaw_node_enabled"
        const val KEY_OPENCLAW_PAIRING_CODE = "openclaw_pairing_code"
        const val KEY_OPENCLAW_DEVICE_ID    = "openclaw_device_id"
        const val KEY_OPENCLAW_DEVICE_TOKEN = "openclaw_device_token"
        const val KEY_OPENCLAW_LLM_BASE_URL = "openclaw_llm_base_url"

        // ── Proactivity ────────────────────────────────────────────────────
        const val KEY_PROACTIVITY_ENABLED              = "proactivity_enabled"
        const val KEY_PROACTIVITY_QUIET_HOURS_ENABLED  = "proactivity_quiet_hours_enabled"
        const val KEY_PROACTIVITY_QUIET_START_MIN      = "proactivity_quiet_start_min"
        const val KEY_PROACTIVITY_QUIET_END_MIN        = "proactivity_quiet_end_min"
        const val KEY_PROACTIVITY_ALLOW_URGENT_QH      = "proactivity_allow_urgent_qh"
        const val KEY_PROACTIVITY_CAT_SUGGESTIONS      = "proactivity_cat_suggestions"
        const val KEY_PROACTIVITY_CAT_REMINDERS        = "proactivity_cat_reminders"
        const val KEY_PROACTIVITY_CAT_LOCATION         = "proactivity_cat_location"
        const val KEY_PROACTIVITY_CAT_HA               = "proactivity_cat_home_assistant"
        const val KEY_PROACTIVITY_CAT_CALENDAR         = "proactivity_cat_calendar"
        const val KEY_PROACTIVITY_CAT_LEARNING         = "proactivity_cat_learning"
        const val KEY_PROACTIVITY_CAT_SAFETY           = "proactivity_cat_safety"
        const val KEY_PROACTIVITY_INTERRUPTION_MODE    = "proactivity_interruption_mode"
        const val KEY_PROACTIVITY_SENSITIVITY          = "proactivity_sensitivity"
        const val KEY_PROACTIVITY_COOLDOWN_MIN         = "proactivity_global_cooldown_minutes"

        // ── Scheduled reminders (Calendar / Todoist / local) ──────────────
        // Per-source enables + offset toggles.  Defaults live in
        // [ScheduledReminderSettings.DEFAULT]; keys mirror the field names.
        const val KEY_SCHED_REMINDERS_CALENDAR_EN    = "sched_reminders_calendar_enabled"
        const val KEY_SCHED_REMINDERS_TODOIST_EN     = "sched_reminders_todoist_enabled"
        const val KEY_SCHED_REMINDERS_LOCAL_EN       = "sched_reminders_local_enabled"
        const val KEY_SCHED_REMINDERS_30M            = "sched_reminders_30m_enabled"
        const val KEY_SCHED_REMINDERS_10M            = "sched_reminders_10m_enabled"
        const val KEY_SCHED_REMINDERS_NOTIFY_FALLBACK= "sched_reminders_notify_fallback"
        const val KEY_SCHED_REMINDERS_BG_SPEECH      = "sched_reminders_background_speech"

        // ── Personality ───────────────────────────────────────────────────
        // Markdown personality files under assets/personality/ are the
        // *content*; these flags are the *policy* knobs the user adjusts
        // in Settings.  Defaults mirror PersonalitySettings.DEFAULT.
        const val KEY_PERSONALITY_ENABLED                = "personality_enabled"
        const val KEY_PERSONALITY_SARCASM_LEVEL          = "personality_sarcasm_level"
        const val KEY_PERSONALITY_JOKE_FREQUENCY         = "personality_joke_frequency"
        const val KEY_PERSONALITY_PUSHBACK_ENABLED       = "personality_pushback_enabled"
        const val KEY_PERSONALITY_ROASTING_ENABLED       = "personality_roasting_enabled"
        const val KEY_PERSONALITY_SERIOUS_AUTODETECT     = "personality_serious_autodetect"
        const val KEY_PERSONALITY_APPLY_TO_PROACTIVE     = "personality_apply_proactive"
        const val KEY_PERSONALITY_APPLY_TO_CONFIRMATIONS = "personality_apply_confirmations"
        const val KEY_PERSONALITY_APPLY_TO_LLM           = "personality_apply_llm"

        // ── Meta Wearables (DAT SDK) ──────────────────────────────────────
        const val KEY_WEARABLES_ENABLED                  = "wearables_enabled"
        const val KEY_WEARABLES_USE_MOCK                 = "wearables_use_mock"
        const val KEY_WEARABLES_AUTO_CONNECT             = "wearables_auto_connect"
        const val KEY_WEARABLES_USE_FOR_LOOK_AT_THIS     = "wearables_use_for_look"
        const val KEY_WEARABLES_SAVE_CAPTURES_TO_GALLERY = "wearables_save_gallery"
        const val KEY_WEARABLES_PREFER_GLASSES_CAMERA    = "wearables_prefer_glasses"
        const val KEY_WEARABLES_VISION_ANALYSIS_ENABLED  = "wearables_vision_enabled"
        const val KEY_WEARABLES_PREFER_ON_DEVICE_VISION  = "wearables_on_device_vision"
        const val KEY_WEARABLES_ALLOW_CLOUD_VISION       = "wearables_cloud_vision"
        const val KEY_WEARABLES_SAVE_VISUAL_HISTORY      = "wearables_save_history"
        const val KEY_WEARABLES_VISUAL_RETENTION_DAYS    = "wearables_history_days"
        const val KEY_WEARABLES_CONFIRM_BEFORE_SHARING   = "wearables_confirm_share"
        const val KEY_WEARABLES_CONFIRM_BEFORE_SAVE_MEM  = "wearables_confirm_save_memory"

        // ── Todoist ────────────────────────────────────────────────────────
        const val KEY_TODOIST_ENABLED            = "todoist_enabled"
        const val KEY_TODOIST_API_TOKEN          = "todoist_api_token"
        const val KEY_TODOIST_DEFAULT_PROJECT    = "todoist_default_project_id"
        const val KEY_TODOIST_DEFAULT_LABELS     = "todoist_default_labels"      // CSV
        const val KEY_TODOIST_DEFAULT_PRIORITY   = "todoist_default_priority"    // enum name
        const val KEY_TODOIST_DEFAULT_TIME_MIN   = "todoist_default_time_min"
        const val KEY_TODOIST_ASK_LABEL          = "todoist_ask_label"
        const val KEY_TODOIST_ASK_TIME           = "todoist_ask_time_when_vague"
        const val KEY_TODOIST_OFFLINE_SYNC       = "todoist_offline_sync"
        const val KEY_TODOIST_VOICE_CONFIRMS     = "todoist_voice_confirms"
        const val KEY_TODOIST_SMART_FOLLOWUP     = "todoist_smart_followup"
        const val KEY_TODOIST_CONTEXTUAL         = "todoist_contextual_reminders"
        const val KEY_TODOIST_REPEAT_NUDGES      = "todoist_repeat_nudges"

        // ── Voice Identity / trust ─────────────────────────────────────────
        // All four default to safe values that prevent owner lockout —
        // see [com.jarvis.assistant.speaker.trust.VoiceTrustState] +
        // CommandPermissionPolicy for the policy matrix.
        const val KEY_VOICE_IDENTITY_ENABLED       = "voice_identity_enabled"
        const val KEY_VOICE_ASSUME_OWNER_ON_START  = "voice_assume_owner_on_start"
        const val KEY_VOICE_STRICT_MODE            = "voice_strict_security_mode"
        const val KEY_VOICE_REQUIRE_FOR_SENSITIVE  = "voice_require_match_sensitive"
        const val KEY_VOICE_ASK_WHO                = "voice_ask_who_when_uncertain"
        const val KEY_VOICE_REAUTH_TIMEOUT_MS      = "voice_reauth_timeout_ms"

        // Defaults
        const val DEFAULT_PROVIDER       = "OpenAI"
        const val DEFAULT_WAKE_WORD      = "Jarvis"
        const val DEFAULT_OLLAMA_URL     = "http://192.168.1.1:11434"
        const val DEFAULT_MINIMAX_URL    = "https://api.minimax.io/v1"
        const val DEFAULT_MINIMAX_MODEL  = "MiniMax-M2.7"
        const val DEFAULT_TTS_VOICE      = ""
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    var llmProvider: String
        get() = prefs.getString(KEY_LLM_PROVIDER, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
        set(v) = prefs.edit().putString(KEY_LLM_PROVIDER, v).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_API_KEY, v).apply()

    var ollamaBaseUrl: String
        get() = prefs.getString(KEY_OLLAMA_BASE_URL, DEFAULT_OLLAMA_URL) ?: DEFAULT_OLLAMA_URL
        set(v) = prefs.edit().putString(KEY_OLLAMA_BASE_URL, v).apply()

    var miniMaxBaseUrl: String
        get() = prefs.getString(KEY_MINIMAX_BASE_URL, DEFAULT_MINIMAX_URL) ?: DEFAULT_MINIMAX_URL
        set(v) = prefs.edit().putString(KEY_MINIMAX_BASE_URL, v).apply()

    var miniMaxModel: String
        get() {
            val stored = prefs.getString(KEY_MINIMAX_MODEL, DEFAULT_MINIMAX_MODEL) ?: DEFAULT_MINIMAX_MODEL
            // "minimax-2.7" was renamed — silently migrate to the current model name.
            val migrated = if (stored == "minimax-2.7") DEFAULT_MINIMAX_MODEL else stored
            // Normalise user-typed variants ("MiniMax-M2.7-highspeed", "M.27",
            // "minimax m2.7 fast", …) to the canonical id MiniMax actually
            // accepts.  Centralised in MiniMaxProvider.canonicalise so both
            // the wire call and the displayed model match.
            return com.jarvis.assistant.llm.providers.MiniMaxProvider.canonicalise(migrated)
        }
        set(v) = prefs.edit().putString(KEY_MINIMAX_MODEL, v).apply()

    var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD
        set(v) = prefs.edit().putString(KEY_WAKE_WORD, v).apply()

    var voiceResponse: Boolean
        get() = prefs.getBoolean(KEY_VOICE_RESPONSE, true)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_RESPONSE, v).apply()

    /**
     * Brave Search API key (free tier at search.brave.com/settings).
     * If blank, DuckDuckGo instant answers are used instead.
     */
    var braveSearchApiKey: String
        get() = prefs.getString(KEY_BRAVE_SEARCH_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BRAVE_SEARCH_KEY, v).apply()

    /**
     * Google Maps Platform API key — enables Places Nearby/Text Search and
     * Distance Matrix routing inside the maps tools.  When blank, the maps
     * tools still work as a thin handoff layer (open Google Maps for the
     * destination) but skip the spoken summary that a key would unlock.
     *
     * Key needs Places API + Distance Matrix API enabled in the Google
     * Cloud console.  Restrict by Android package + SHA-1 fingerprint in
     * production.
     */
    var googleMapsApiKey: String
        get() = prefs.getString(KEY_GOOGLE_MAPS_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_GOOGLE_MAPS_KEY, v).apply()

    // ── GitHub auto-issue reporting (owner/dev mode) ──────────────────────────
    //
    // All four live in EncryptedSharedPreferences alongside the rest of the
    // settings, so the token never lands on disk in plaintext and is wiped
    // with the app's data directory.  The master key is held in the Android
    // Keystore and doesn't survive device transfer — if you restore Jarvis on
    // a new phone, re-enter the token; nothing will silently attempt to use
    // an old one.
    //
    // githubReportingEnabled defaults to false.  In a shared/public build the
    // recommendation is to leave it false and either disable the feature
    // branch entirely, or route the reports elsewhere (see IssueReporter).

    var githubReportingEnabled: Boolean
        get() = prefs.getBoolean(KEY_GH_REPORTING_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_GH_REPORTING_ENABLED, v).apply()

    var githubRepoOwner: String
        get() = prefs.getString(KEY_GH_REPO_OWNER, DEFAULT_GH_REPO_OWNER) ?: DEFAULT_GH_REPO_OWNER
        set(v) = prefs.edit().putString(KEY_GH_REPO_OWNER, v).apply()

    var githubRepoName: String
        get() = prefs.getString(KEY_GH_REPO_NAME, DEFAULT_GH_REPO_NAME) ?: DEFAULT_GH_REPO_NAME
        set(v) = prefs.edit().putString(KEY_GH_REPO_NAME, v).apply()

    /**
     * GitHub personal-access token used to create issues in [githubRepoOwner]/
     * [githubRepoName].  Stored under EncryptedSharedPreferences.  Never log
     * this value; never include it in crash reports or metadata.
     */
    var githubToken: String
        get() = prefs.getString(KEY_GH_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_GH_TOKEN, v).apply()

    /** Name of the TTS voice to use. Defaults to the local Piper ONNX voice. */
    var ttsVoiceName: String
        get() = prefs.getString(KEY_TTS_VOICE_NAME, DEFAULT_TTS_VOICE) ?: DEFAULT_TTS_VOICE
        set(v) = prefs.edit().putString(KEY_TTS_VOICE_NAME, v).apply()

    /**
     * Default messaging channel: "sms", "whatsapp", or "ask".
     * "ask" means Jarvis will ask the user each time.
     * "whatsapp" prefixed commands ("WhatsApp Chris") always override this.
     */
    var defaultMsgChannel: String
        get() = prefs.getString(KEY_DEFAULT_MSG_CHANNEL, "ask") ?: "ask"
        set(v) = prefs.edit().putString(KEY_DEFAULT_MSG_CHANNEL, v).apply()

    // ── OpenAI OAuth ───────────────────────────────────────────────────────

    var openAiClientId: String
        get() = prefs.getString(KEY_OPENAI_CLIENT_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENAI_CLIENT_ID, v).apply()

    var openAiAccessToken: String
        get() = prefs.getString(KEY_OPENAI_ACCESS_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENAI_ACCESS_TOKEN, v).apply()

    var openAiRefreshToken: String
        get() = prefs.getString(KEY_OPENAI_REFRESH_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENAI_REFRESH_TOKEN, v).apply()

    /** Temporary PKCE verifier stored while the browser is open. */
    var openAiCodeVerifier: String
        get() = prefs.getString(KEY_OPENAI_CODE_VERIFIER, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENAI_CODE_VERIFIER, v).apply()

    var openAiOAuthEnabled: Boolean
        get() = prefs.getBoolean(KEY_OPENAI_OAUTH_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_OPENAI_OAUTH_ENABLED, v).apply()

    // ── HermesAgent (Phase 6) ───────────────────────────────────────────────

    var hermesEnabled: Boolean
        get() = prefs.getBoolean(KEY_HERMES_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_HERMES_ENABLED, v).apply()

    var hermesHost: String
        get() = prefs.getString(KEY_HERMES_HOST, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HERMES_HOST, v).apply()

    var hermesPort: Int
        get() = prefs.getInt(KEY_HERMES_PORT, DEFAULT_HERMES_PORT)
        set(v) = prefs.edit().putInt(KEY_HERMES_PORT, v).apply()

    var hermesSecure: Boolean
        get() = prefs.getBoolean(KEY_HERMES_SECURE, false)
        set(v) = prefs.edit().putBoolean(KEY_HERMES_SECURE, v).apply()

    /** API_SERVER_KEY for the Hermes /v1 + /api/jobs endpoints. */
    var hermesApiKey: String
        get() = prefs.getString(KEY_HERMES_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HERMES_API_KEY, v).apply()

    /** Hermes profile / advertised model name (defaults to "hermes-agent"). */
    var hermesProfile: String
        get() = prefs.getString(KEY_HERMES_PROFILE, DEFAULT_HERMES_PROFILE) ?: DEFAULT_HERMES_PROFILE
        set(v) = prefs.edit().putString(KEY_HERMES_PROFILE, v).apply()

    var hermesTimeoutMs: Long
        get() = prefs.getLong(KEY_HERMES_TIMEOUT_MS, DEFAULT_HERMES_TIMEOUT)
        set(v) = prefs.edit().putLong(KEY_HERMES_TIMEOUT_MS, v).apply()

    // ── OpenClaw ───────────────────────────────────────────────────────────

    var openClawEnabled: Boolean
        get() = prefs.getBoolean(KEY_OPENCLAW_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_OPENCLAW_ENABLED, v).apply()

    var openClawHost: String
        get() = prefs.getString(KEY_OPENCLAW_HOST, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENCLAW_HOST, v).apply()

    var openClawPort: Int
        get() = prefs.getInt(KEY_OPENCLAW_PORT, 8765)
        set(v) = prefs.edit().putInt(KEY_OPENCLAW_PORT, v).apply()

    var openClawSecure: Boolean
        get() = prefs.getBoolean(KEY_OPENCLAW_SECURE, false)
        set(v) = prefs.edit().putBoolean(KEY_OPENCLAW_SECURE, v).apply()

    /** Auth token stored encrypted — same encryption as the LLM API key. */
    var openClawAuthToken: String
        get() = prefs.getString(KEY_OPENCLAW_AUTH_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENCLAW_AUTH_TOKEN, v).apply()

    var openClawTimeoutMs: Long
        get() = prefs.getLong(KEY_OPENCLAW_TIMEOUT_MS, 90_000L)
        set(v) = prefs.edit().putLong(KEY_OPENCLAW_TIMEOUT_MS, v).apply()

    var openClawModel: String
        get() = prefs.getString(KEY_OPENCLAW_MODEL, "openclaw/default") ?: "openclaw/default"
        set(v) = prefs.edit().putString(KEY_OPENCLAW_MODEL, v).apply()

    /** Trigger keyword that forces routing to OpenClaw (default: "computer"). */
    var openClawKeyword: String
        get() = prefs.getString(KEY_OPENCLAW_KEYWORD, "computer") ?: "computer"
        set(v) = prefs.edit().putString(KEY_OPENCLAW_KEYWORD, v).apply()

    /** Whether Jarvis registers as an OpenClaw node so the gateway can invoke tools on the phone. */
    var openClawNodeEnabled: Boolean
        get() = prefs.getBoolean(KEY_OPENCLAW_NODE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_OPENCLAW_NODE_ENABLED, v).apply()

    /**
     * Optional pairing code shown by the OpenClaw gateway during the initial
     * approval flow.  When set, it is sent inside the `connect` request's
     * `auth.pairingCode` field on the next reconnect; the gateway uses it
     * to auto-approve this device without a CLI `openclaw devices approve`
     * step.  Cleared by the gateway once approval is exchanged for a
     * deviceToken.
     */
    var openClawPairingCode: String
        get() = prefs.getString(KEY_OPENCLAW_PAIRING_CODE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENCLAW_PAIRING_CODE, v).apply()

    /** Stable device ID generated once and used in the OpenClaw handshake. */
    var openClawDeviceId: String
        get() {
            var id = prefs.getString(KEY_OPENCLAW_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_OPENCLAW_DEVICE_ID, id).apply()
            }
            return id
        }
        set(v) = prefs.edit().putString(KEY_OPENCLAW_DEVICE_ID, v).apply()

    /** Device token returned by the gateway after successful pairing; used on reconnects. */
    var openClawDeviceToken: String
        get() = prefs.getString(KEY_OPENCLAW_DEVICE_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENCLAW_DEVICE_TOKEN, v).apply()

    /**
     * Optional base URL that overrides the LLM endpoint while leaving the gateway
     * (WebSocket node connection) on the primary host:port.
     * E.g. "http://openclaw.tail48466.ts.net:8642" to route queries to Hermes.
     * Blank = derive from host:port as normal.
     */
    var openClawLlmBaseUrl: String
        get() = prefs.getString(KEY_OPENCLAW_LLM_BASE_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENCLAW_LLM_BASE_URL, v).apply()

    /**
     * One-shot trigger: when ≥ 0, JarvisRuntime will auto-start voice enrollment
     * for this personId at the next session start, then reset the value to -1.
     * Written by the Settings UI; consumed (and cleared) by JarvisRuntime.
     */
    var pendingVoiceEnrollmentPersonId: Long
        get() = prefs.getLong(KEY_PENDING_ENROLL_PID, -1L)
        set(v) = prefs.edit().putLong(KEY_PENDING_ENROLL_PID, v).apply()

    /** Max tokens the LLM may generate per response. Range 400–4000. Default 1200. */
    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
        set(v) = prefs.edit().putInt(KEY_MAX_TOKENS, v).apply()

    /** Secondary provider name tried when the primary fails twice. Empty = disabled. */
    var fallbackProvider: String
        get() = prefs.getString(KEY_FALLBACK_PROVIDER, "") ?: ""
        set(v) = prefs.edit().putString(KEY_FALLBACK_PROVIDER, v).apply()

    // ── Proactivity ────────────────────────────────────────────────────────
    // Settings backing the user-visible Proactivity screen.  Defaults are
    // sourced from [ProactivitySettings.DEFAULT] so a single change there
    // propagates everywhere.  Each field is its own pref key to match the
    // existing storage pattern in this file.

    var proactivityEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVITY_ENABLED,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.enabled)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVITY_ENABLED, v).apply()

    var proactivityQuietHoursEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVITY_QUIET_HOURS_ENABLED,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.quietHoursEnabled)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVITY_QUIET_HOURS_ENABLED, v).apply()

    /** Quiet-hours start, minutes-from-midnight (0..1439). */
    var proactivityQuietStartMinute: Int
        get() = prefs.getInt(KEY_PROACTIVITY_QUIET_START_MIN,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.quietStartMinute)
        set(v) = prefs.edit().putInt(KEY_PROACTIVITY_QUIET_START_MIN, v.coerceIn(0, 1439)).apply()

    /** Quiet-hours end, minutes-from-midnight (0..1439). */
    var proactivityQuietEndMinute: Int
        get() = prefs.getInt(KEY_PROACTIVITY_QUIET_END_MIN,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.quietEndMinute)
        set(v) = prefs.edit().putInt(KEY_PROACTIVITY_QUIET_END_MIN, v.coerceIn(0, 1439)).apply()

    var proactivityAllowUrgentDuringQuietHours: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVITY_ALLOW_URGENT_QH,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.allowUrgentDuringQuietHours)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVITY_ALLOW_URGENT_QH, v).apply()

    var proactivitySuggestionsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVITY_CAT_SUGGESTIONS,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.suggestionsEnabled)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVITY_CAT_SUGGESTIONS, v).apply()

    var proactivityRemindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVITY_CAT_REMINDERS,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.remindersEnabled)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVITY_CAT_REMINDERS, v).apply()

    var proactivityLocationAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVITY_CAT_LOCATION,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.locationAlertsEnabled)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVITY_CAT_LOCATION, v).apply()

    var proactivityHomeAssistantAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVITY_CAT_HA,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.homeAssistantAlertsEnabled)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVITY_CAT_HA, v).apply()

    var proactivityCalendarNudgesEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVITY_CAT_CALENDAR,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.calendarNudgesEnabled)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVITY_CAT_CALENDAR, v).apply()

    var proactivityLearningObservationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVITY_CAT_LEARNING,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.learningObservationsEnabled)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVITY_CAT_LEARNING, v).apply()

    var proactivitySafetySecurityAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROACTIVITY_CAT_SAFETY,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.safetySecurityAlertsEnabled)
        set(v) = prefs.edit().putBoolean(KEY_PROACTIVITY_CAT_SAFETY, v).apply()

    /** [InterruptionMode] stored by .name; unknown values fall back to default. */
    var proactivityInterruptionMode: com.jarvis.assistant.proactive.settings.InterruptionMode
        get() {
            val stored = prefs.getString(KEY_PROACTIVITY_INTERRUPTION_MODE, null)
            return stored?.let {
                runCatching {
                    com.jarvis.assistant.proactive.settings.InterruptionMode.valueOf(it)
                }.getOrNull()
            } ?: com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.interruptionMode
        }
        set(v) = prefs.edit().putString(KEY_PROACTIVITY_INTERRUPTION_MODE, v.name).apply()

    /** [ProactivitySensitivity] stored by .name; unknown values fall back to default. */
    var proactivitySensitivity: com.jarvis.assistant.proactive.settings.ProactivitySensitivity
        get() {
            val stored = prefs.getString(KEY_PROACTIVITY_SENSITIVITY, null)
            return stored?.let {
                runCatching {
                    com.jarvis.assistant.proactive.settings.ProactivitySensitivity.valueOf(it)
                }.getOrNull()
            } ?: com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.sensitivity
        }
        set(v) = prefs.edit().putString(KEY_PROACTIVITY_SENSITIVITY, v.name).apply()

    /** Minimum gap between any two proactive surfacings, in whole minutes. */
    var proactivityGlobalCooldownMinutes: Int
        get() = prefs.getInt(KEY_PROACTIVITY_COOLDOWN_MIN,
            com.jarvis.assistant.proactive.settings.ProactivitySettings.DEFAULT.globalCooldownMinutes)
        set(v) = prefs.edit().putInt(KEY_PROACTIVITY_COOLDOWN_MIN, v.coerceIn(1, 240)).apply()

    // ── Scheduled reminders (Calendar / Todoist / local) ───────────────────

    var schedRemindersCalendarEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHED_REMINDERS_CALENDAR_EN, true)
        set(v) = prefs.edit().putBoolean(KEY_SCHED_REMINDERS_CALENDAR_EN, v).apply()

    var schedRemindersTodoistEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHED_REMINDERS_TODOIST_EN, true)
        set(v) = prefs.edit().putBoolean(KEY_SCHED_REMINDERS_TODOIST_EN, v).apply()

    var schedRemindersLocalEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHED_REMINDERS_LOCAL_EN, true)
        set(v) = prefs.edit().putBoolean(KEY_SCHED_REMINDERS_LOCAL_EN, v).apply()

    var schedReminders30mEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHED_REMINDERS_30M, true)
        set(v) = prefs.edit().putBoolean(KEY_SCHED_REMINDERS_30M, v).apply()

    var schedReminders10mEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCHED_REMINDERS_10M, true)
        set(v) = prefs.edit().putBoolean(KEY_SCHED_REMINDERS_10M, v).apply()

    var schedRemindersNotifyFallback: Boolean
        get() = prefs.getBoolean(KEY_SCHED_REMINDERS_NOTIFY_FALLBACK, true)
        set(v) = prefs.edit().putBoolean(KEY_SCHED_REMINDERS_NOTIFY_FALLBACK, v).apply()

    var schedRemindersBackgroundSpeech: Boolean
        get() = prefs.getBoolean(KEY_SCHED_REMINDERS_BG_SPEECH, false)
        set(v) = prefs.edit().putBoolean(KEY_SCHED_REMINDERS_BG_SPEECH, v).apply()

    // ── Personality ────────────────────────────────────────────────────────

    var personalityEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERSONALITY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_PERSONALITY_ENABLED, v).apply()

    var personalitySarcasmLevel: com.jarvis.assistant.personality.SarcasmLevel
        get() = prefs.getString(KEY_PERSONALITY_SARCASM_LEVEL, null)?.let {
            runCatching { com.jarvis.assistant.personality.SarcasmLevel.valueOf(it) }.getOrNull()
        } ?: com.jarvis.assistant.personality.SarcasmLevel.MEDIUM
        set(v) = prefs.edit().putString(KEY_PERSONALITY_SARCASM_LEVEL, v.name).apply()

    var personalityJokeFrequency: com.jarvis.assistant.personality.JokeFrequency
        get() = prefs.getString(KEY_PERSONALITY_JOKE_FREQUENCY, null)?.let {
            runCatching { com.jarvis.assistant.personality.JokeFrequency.valueOf(it) }.getOrNull()
        } ?: com.jarvis.assistant.personality.JokeFrequency.SOMETIMES
        set(v) = prefs.edit().putString(KEY_PERSONALITY_JOKE_FREQUENCY, v.name).apply()

    var personalityPushbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERSONALITY_PUSHBACK_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_PERSONALITY_PUSHBACK_ENABLED, v).apply()

    var personalityFriendlyRoastingEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERSONALITY_ROASTING_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_PERSONALITY_ROASTING_ENABLED, v).apply()

    var personalitySeriousAutoDetectEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERSONALITY_SERIOUS_AUTODETECT, true)
        set(v) = prefs.edit().putBoolean(KEY_PERSONALITY_SERIOUS_AUTODETECT, v).apply()

    var personalityApplyToProactiveReminders: Boolean
        get() = prefs.getBoolean(KEY_PERSONALITY_APPLY_TO_PROACTIVE, true)
        set(v) = prefs.edit().putBoolean(KEY_PERSONALITY_APPLY_TO_PROACTIVE, v).apply()

    var personalityApplyToLocalConfirmations: Boolean
        get() = prefs.getBoolean(KEY_PERSONALITY_APPLY_TO_CONFIRMATIONS, true)
        set(v) = prefs.edit().putBoolean(KEY_PERSONALITY_APPLY_TO_CONFIRMATIONS, v).apply()

    var personalityApplyToLlmAnswers: Boolean
        get() = prefs.getBoolean(KEY_PERSONALITY_APPLY_TO_LLM, true)
        set(v) = prefs.edit().putBoolean(KEY_PERSONALITY_APPLY_TO_LLM, v).apply()

    // ── Meta Wearables ─────────────────────────────────────────────────────
    // Defaults mirror WearablesSettings.DEFAULT — opt-in everywhere.

    var wearablesEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_ENABLED, v).apply()

    var wearablesUseMockDevice: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_USE_MOCK, false)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_USE_MOCK, v).apply()

    var wearablesAutoConnectOnStart: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_AUTO_CONNECT, false)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_AUTO_CONNECT, v).apply()

    var wearablesUseForLookAtThis: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_USE_FOR_LOOK_AT_THIS, true)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_USE_FOR_LOOK_AT_THIS, v).apply()

    var wearablesSaveCapturesToGallery: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_SAVE_CAPTURES_TO_GALLERY, true)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_SAVE_CAPTURES_TO_GALLERY, v).apply()

    var wearablesPreferGlassesCamera: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_PREFER_GLASSES_CAMERA, true)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_PREFER_GLASSES_CAMERA, v).apply()

    var wearablesVisionAnalysisEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_VISION_ANALYSIS_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_VISION_ANALYSIS_ENABLED, v).apply()

    var wearablesPreferOnDeviceVision: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_PREFER_ON_DEVICE_VISION, true)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_PREFER_ON_DEVICE_VISION, v).apply()

    var wearablesAllowCloudVision: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_ALLOW_CLOUD_VISION, false)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_ALLOW_CLOUD_VISION, v).apply()

    var wearablesSaveVisualHistory: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_SAVE_VISUAL_HISTORY, false)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_SAVE_VISUAL_HISTORY, v).apply()

    var wearablesVisualHistoryRetentionDays: Int
        get() = prefs.getInt(KEY_WEARABLES_VISUAL_RETENTION_DAYS, 7)
        set(v) = prefs.edit().putInt(KEY_WEARABLES_VISUAL_RETENTION_DAYS, v.coerceIn(1, 90)).apply()

    var wearablesConfirmBeforeSharing: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_CONFIRM_BEFORE_SHARING, true)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_CONFIRM_BEFORE_SHARING, v).apply()

    var wearablesConfirmBeforeSavingMemory: Boolean
        get() = prefs.getBoolean(KEY_WEARABLES_CONFIRM_BEFORE_SAVE_MEM, true)
        set(v) = prefs.edit().putBoolean(KEY_WEARABLES_CONFIRM_BEFORE_SAVE_MEM, v).apply()

    // ── Todoist ────────────────────────────────────────────────────────────
    // The user's Todoist integration settings — backing
    // [com.jarvis.assistant.todoist.TodoistSettings].  Defaults are
    // sourced from [TodoistSettings.DEFAULT].

    var todoistEnabled: Boolean
        get() = prefs.getBoolean(KEY_TODOIST_ENABLED,
            com.jarvis.assistant.todoist.TodoistSettings.DEFAULT.enabled)
        set(v) = prefs.edit().putBoolean(KEY_TODOIST_ENABLED, v).apply()

    /** Personal Todoist API token.  Stored in the encrypted prefs file. */
    var todoistApiToken: String
        get() = prefs.getString(KEY_TODOIST_API_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TODOIST_API_TOKEN, v.trim()).apply()

    var todoistDefaultProjectId: String
        get() = prefs.getString(KEY_TODOIST_DEFAULT_PROJECT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TODOIST_DEFAULT_PROJECT, v).apply()

    /** Stored as CSV; empty string = no defaults. */
    var todoistDefaultLabelsCsv: String
        get() = prefs.getString(KEY_TODOIST_DEFAULT_LABELS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TODOIST_DEFAULT_LABELS, v).apply()

    var todoistDefaultPriority: com.jarvis.assistant.todoist.TodoistPriority
        get() {
            val stored = prefs.getString(KEY_TODOIST_DEFAULT_PRIORITY, null)
            return stored?.let {
                runCatching { com.jarvis.assistant.todoist.TodoistPriority.valueOf(it) }
                    .getOrNull()
            } ?: com.jarvis.assistant.todoist.TodoistSettings.DEFAULT.defaultPriority
        }
        set(v) = prefs.edit().putString(KEY_TODOIST_DEFAULT_PRIORITY, v.name).apply()

    var todoistDefaultReminderMinuteOfDay: Int
        get() = prefs.getInt(KEY_TODOIST_DEFAULT_TIME_MIN,
            com.jarvis.assistant.todoist.TodoistSettings.DEFAULT.defaultReminderMinuteOfDay)
        set(v) = prefs.edit().putInt(KEY_TODOIST_DEFAULT_TIME_MIN,
            v.coerceIn(0, 1439)).apply()

    var todoistAskForLabelAfterCreate: Boolean
        get() = prefs.getBoolean(KEY_TODOIST_ASK_LABEL,
            com.jarvis.assistant.todoist.TodoistSettings.DEFAULT.askForLabelAfterCreate)
        set(v) = prefs.edit().putBoolean(KEY_TODOIST_ASK_LABEL, v).apply()

    var todoistAskForTimeWhenDateVague: Boolean
        get() = prefs.getBoolean(KEY_TODOIST_ASK_TIME,
            com.jarvis.assistant.todoist.TodoistSettings.DEFAULT.askForTimeWhenDateVague)
        set(v) = prefs.edit().putBoolean(KEY_TODOIST_ASK_TIME, v).apply()

    var todoistOfflineSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_TODOIST_OFFLINE_SYNC,
            com.jarvis.assistant.todoist.TodoistSettings.DEFAULT.offlineSyncEnabled)
        set(v) = prefs.edit().putBoolean(KEY_TODOIST_OFFLINE_SYNC, v).apply()

    var todoistVoiceConfirmationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TODOIST_VOICE_CONFIRMS,
            com.jarvis.assistant.todoist.TodoistSettings.DEFAULT.voiceConfirmationsEnabled)
        set(v) = prefs.edit().putBoolean(KEY_TODOIST_VOICE_CONFIRMS, v).apply()

    var todoistSmartFollowUpEnabled: Boolean
        get() = prefs.getBoolean(KEY_TODOIST_SMART_FOLLOWUP,
            com.jarvis.assistant.todoist.TodoistSettings.DEFAULT.smartFollowUpEnabled)
        set(v) = prefs.edit().putBoolean(KEY_TODOIST_SMART_FOLLOWUP, v).apply()

    var todoistContextualRemindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_TODOIST_CONTEXTUAL,
            com.jarvis.assistant.todoist.TodoistSettings.DEFAULT.contextualRemindersEnabled)
        set(v) = prefs.edit().putBoolean(KEY_TODOIST_CONTEXTUAL, v).apply()

    var todoistRepeatingNudgesEnabled: Boolean
        get() = prefs.getBoolean(KEY_TODOIST_REPEAT_NUDGES,
            com.jarvis.assistant.todoist.TodoistSettings.DEFAULT.repeatingReminderNudgesEnabled)
        set(v) = prefs.edit().putBoolean(KEY_TODOIST_REPEAT_NUDGES, v).apply()

    // ── Voice Identity / trust ─────────────────────────────────────────────
    // Defaults chosen so a fresh install or any-state restart NEVER locks
    // the owner out of phone commands.  See
    // [com.jarvis.assistant.speaker.trust.VoiceTrustState] for the
    // permission matrix backing these flags.

    /** Master switch for speaker-recognition personalisation features. */
    var voiceIdentityEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_IDENTITY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_IDENTITY_ENABLED, v).apply()

    /**
     * When ON (default), every app start and every Start-button press
     * sets the trust state to OWNER_ASSUMED so local commands work
     * immediately.  Disabling this means the owner MUST be voice-matched
     * before any sensitive command — only flip if you genuinely have a
     * multi-user setup with hostile actors.
     */
    var voiceAssumeOwnerOnStart: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ASSUME_OWNER_ON_START, true)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_ASSUME_OWNER_ON_START, v).apply()

    /**
     * Strict voice-security mode.  When OFF (default), unknown / low-
     * confidence speakers still execute LOW_RISK and MEDIUM_RISK
     * commands as OWNER_ASSUMED.  When ON, MEDIUM_RISK and HIGH_RISK
     * commands require a reauth or voice match.
     */
    var voiceStrictMode: Boolean
        get() = prefs.getBoolean(KEY_VOICE_STRICT_MODE, false)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_STRICT_MODE, v).apply()

    /** Require a voice match for HIGH_RISK actions (door unlock, etc.). */
    var voiceRequireMatchForSensitive: Boolean
        get() = prefs.getBoolean(KEY_VOICE_REQUIRE_FOR_SENSITIVE, false)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_REQUIRE_FOR_SENSITIVE, v).apply()

    /** Ask "Who is this?" when voice identity is uncertain.  Default ON. */
    var voiceAskWhoWhenUncertain: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ASK_WHO, true)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_ASK_WHO, v).apply()

    /** How long a reauth challenge stays pending before expiring. */
    var voiceReauthTimeoutMs: Long
        get() = prefs.getLong(KEY_VOICE_REAUTH_TIMEOUT_MS, 60_000L)
        set(v) = prefs.edit().putLong(KEY_VOICE_REAUTH_TIMEOUT_MS,
            v.coerceIn(10_000L, 300_000L)).apply()

    // ── Home Assistant ─────────────────────────────────────────────────────

    var haBaseUrl: String
        get() = prefs.getString(KEY_HA_BASE_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HA_BASE_URL, v).apply()

    var haApiToken: String
        get() = prefs.getString(KEY_HA_API_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HA_API_TOKEN, v).apply()

    // ── Cloud sync (Firebase, optional) ────────────────────────────────────
    //
    // Everything here is user-entered. The CloudSyncService only initialises
    // Firebase when all four credentials are non-blank and cloudSyncEnabled
    // is true, so a fresh install does nothing until the user opts in.

    var cloudSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, v).apply()

    var firebaseApiKey: String
        get() = prefs.getString(KEY_FIREBASE_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_FIREBASE_API_KEY, v).apply()

    var firebaseAppId: String
        get() = prefs.getString(KEY_FIREBASE_APP_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_FIREBASE_APP_ID, v).apply()

    var firebaseProjectId: String
        get() = prefs.getString(KEY_FIREBASE_PROJECT_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_FIREBASE_PROJECT_ID, v).apply()

    var firebaseDbUrl: String
        get() = prefs.getString(KEY_FIREBASE_DB_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_FIREBASE_DB_URL, v).apply()

    var cloudSyncEmail: String
        get() = prefs.getString(KEY_CLOUD_SYNC_EMAIL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CLOUD_SYNC_EMAIL, v).apply()

    var cloudSyncLastMs: Long
        get() = prefs.getLong(KEY_CLOUD_SYNC_LAST_MS, 0L)
        set(v) = prefs.edit().putLong(KEY_CLOUD_SYNC_LAST_MS, v).apply()

    /** All four Firebase credentials present and non-blank. */
    fun firebaseConfigured(): Boolean =
        firebaseApiKey.isNotBlank() && firebaseAppId.isNotBlank() &&
            firebaseProjectId.isNotBlank()

    // ── Safety / lifecycle ────────────────────────────────────────────────

    /**
     * Kill switch.  When true, [ActionPolicyGate.evaluate] denies every tool
     * execution with [DenialReason.DISABLED_BY_POLICY].  Conversation still
     * flows through the LLM — the user just can't trigger any device action
     * until the flag is flipped back.
     */
    var toolExecutionDisabled: Boolean
        get() = prefs.getBoolean(KEY_TOOL_EXECUTION_DISABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_TOOL_EXECUTION_DISABLED, v).apply()

    /**
     * When true (default), [BootReceiver] auto-starts [JarvisService] on
     * device boot.  Set to false to keep Jarvis off until the user launches
     * the app manually.
     */
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ON_BOOT, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_START_ON_BOOT, v).apply()

    // ── Appearance (Phase 2) ────────────────────────────────────────────────

    /**
     * Theme mode: "system" | "light" | "dark" | "amoled".
     * AMOLED is a strict-black variant for OLED battery savings on this
     * always-on app — never selected implicitly even when SYSTEM is set.
     */
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
        set(v) = prefs.edit().putString(KEY_THEME_MODE, v).apply()

    /**
     * When true and the device is Android 12+, derives the colour scheme
     * from the user's wallpaper.  AMOLED mode ignores this.
     */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(v) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, v).apply()

    // ── App lock (Phase 4b) ──────────────────────────────────────────────────

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, v).apply()

    var appLockBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_BIOMETRIC, false)
        set(v) = prefs.edit().putBoolean(KEY_APP_LOCK_BIOMETRIC, v).apply()

    /** PBKDF2-HmacSHA256 PIN hash, Base64-encoded. Empty until the user sets a PIN. */
    var appLockPinHash: String
        get() = prefs.getString(KEY_APP_LOCK_PIN_HASH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_APP_LOCK_PIN_HASH, v).apply()

    /** Base64-encoded random salt paired with [appLockPinHash]. */
    var appLockPinSalt: String
        get() = prefs.getString(KEY_APP_LOCK_PIN_SALT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_APP_LOCK_PIN_SALT, v).apply()

    var appLockLastUnlockMs: Long
        get() = prefs.getLong(KEY_APP_LOCK_LAST_UNLOCK, 0L)
        set(v) = prefs.edit().putLong(KEY_APP_LOCK_LAST_UNLOCK, v).apply()

    /** Forget PIN + biometric opt-in — called by AppLockManager.disableLock(). */
    fun clearAppLock() {
        prefs.edit()
            .remove(KEY_APP_LOCK_ENABLED)
            .remove(KEY_APP_LOCK_BIOMETRIC)
            .remove(KEY_APP_LOCK_PIN_HASH)
            .remove(KEY_APP_LOCK_PIN_SALT)
            .remove(KEY_APP_LOCK_LAST_UNLOCK)
            .apply()
    }

    fun clearOpenAiOAuth() {
        prefs.edit()
            .remove(KEY_OPENAI_ACCESS_TOKEN)
            .remove(KEY_OPENAI_REFRESH_TOKEN)
            .remove(KEY_OPENAI_CODE_VERIFIER)
            .putBoolean(KEY_OPENAI_OAUTH_ENABLED, false)
            .apply()
    }
}
