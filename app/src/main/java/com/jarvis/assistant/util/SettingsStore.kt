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
        const val KEY_DEFAULT_MSG_CHANNEL    = "default_msg_channel"

        // OpenAI OAuth keys
        const val KEY_OPENAI_CLIENT_ID     = "openai_client_id"
        const val KEY_OPENAI_ACCESS_TOKEN  = "openai_access_token"
        const val KEY_OPENAI_REFRESH_TOKEN = "openai_refresh_token"
        const val KEY_OPENAI_CODE_VERIFIER = "openai_code_verifier"
        const val KEY_OPENAI_OAUTH_ENABLED = "openai_oauth_enabled"

        // OpenClaw remote routing keys
        const val KEY_OPENCLAW_ENABLED    = "openclaw_enabled"
        const val KEY_OPENCLAW_HOST       = "openclaw_host"
        const val KEY_OPENCLAW_PORT       = "openclaw_port"
        const val KEY_OPENCLAW_SECURE     = "openclaw_secure"
        const val KEY_OPENCLAW_AUTH_TOKEN = "openclaw_auth_token"
        const val KEY_OPENCLAW_TIMEOUT_MS = "openclaw_timeout_ms"

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

    fun clearOpenAiOAuth() {
        prefs.edit()
            .remove(KEY_OPENAI_ACCESS_TOKEN)
            .remove(KEY_OPENAI_REFRESH_TOKEN)
            .remove(KEY_OPENAI_CODE_VERIFIER)
            .putBoolean(KEY_OPENAI_OAUTH_ENABLED, false)
            .apply()
    }
}
