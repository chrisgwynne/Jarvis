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

    fun clearOpenAiOAuth() {
        prefs.edit()
            .remove(KEY_OPENAI_ACCESS_TOKEN)
            .remove(KEY_OPENAI_REFRESH_TOKEN)
            .remove(KEY_OPENAI_CODE_VERIFIER)
            .putBoolean(KEY_OPENAI_OAUTH_ENABLED, false)
            .apply()
    }
}
