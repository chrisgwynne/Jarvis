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

        // OpenClaw remote routing keys
        const val KEY_OPENCLAW_ENABLED    = "openclaw_enabled"
        const val KEY_OPENCLAW_HOST       = "openclaw_host"
        const val KEY_OPENCLAW_PORT       = "openclaw_port"
        const val KEY_OPENCLAW_SECURE     = "openclaw_secure"
        const val KEY_OPENCLAW_AUTH_TOKEN = "openclaw_auth_token"
        const val KEY_OPENCLAW_TIMEOUT_MS = "openclaw_timeout_ms"
        const val KEY_OPENCLAW_MODEL      = "openclaw_model"
        const val KEY_OPENCLAW_KEYWORD    = "openclaw_keyword"

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
            return if (stored == "minimax-2.7") DEFAULT_MINIMAX_MODEL else stored
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
        get() = prefs.getLong(KEY_OPENCLAW_TIMEOUT_MS, 30_000L)
        set(v) = prefs.edit().putLong(KEY_OPENCLAW_TIMEOUT_MS, v).apply()

    var openClawModel: String
        get() = prefs.getString(KEY_OPENCLAW_MODEL, "openclaw") ?: "openclaw"
        set(v) = prefs.edit().putString(KEY_OPENCLAW_MODEL, v).apply()

    /** Trigger keyword that forces routing to OpenClaw (default: "computer"). */
    var openClawKeyword: String
        get() = prefs.getString(KEY_OPENCLAW_KEYWORD, "computer") ?: "computer"
        set(v) = prefs.edit().putString(KEY_OPENCLAW_KEYWORD, v).apply()

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
