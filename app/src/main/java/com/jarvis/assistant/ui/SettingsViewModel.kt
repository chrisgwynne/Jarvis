package com.jarvis.assistant.ui

import android.app.Application
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.audio.TtsEngine
import com.jarvis.assistant.auth.OAuthCallbackHolder
import com.jarvis.assistant.auth.OpenAiOAuthManager
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.memory.db.JarvisDatabase
import com.jarvis.assistant.remote.openclaw.OpenClawClient
import com.jarvis.assistant.remote.openclaw.OpenClawConnectionStatus
import com.jarvis.assistant.remote.openclaw.OpenClawError
import com.jarvis.assistant.remote.openclaw.OpenClawExecutionResult
import com.jarvis.assistant.remote.openclaw.OpenClawHealthMonitor
import com.jarvis.assistant.remote.openclaw.OpenClawRequest
import com.jarvis.assistant.remote.openclaw.OpenClawSettingsRepository
import com.jarvis.assistant.remote.openclaw.RouteType
import com.jarvis.assistant.speaker.SpeakerProfileStore
import com.jarvis.assistant.speaker.db.PersonRecord
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/** A TTS voice entry shown in the voice picker. */
data class TtsVoiceInfo(
    /** Internal key stored in [SettingsStore.ttsVoiceName]. */
    val name: String,
    /** Human-readable label shown in the UI. */
    val displayName: String,
    /** True if the voice requires a network connection. */
    val isNetwork: Boolean
)

/**
 * SettingsViewModel — bridges SettingsStore (disk) and the Compose UI.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    val providers = listOf("OpenAI", "Anthropic", "Gemini", "Ollama", "OpenRouter", "Kimi", "MiniMax")

    private val store         = SettingsStore(app)
    private val openClawRepo  = OpenClawSettingsRepository(store)
    private val db            = JarvisDatabase.getInstance(app)
    private val speakerStore  = SpeakerProfileStore(db.personRecordDao(), db.speakerEmbeddingDao())

    // ── Exposed state ──────────────────────────────────────────────────────

    private val _llmProvider = MutableStateFlow(store.llmProvider)
    val llmProvider: StateFlow<String> = _llmProvider.asStateFlow()

    private val _apiKey = MutableStateFlow(store.apiKey)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _ollamaBaseUrl = MutableStateFlow(store.ollamaBaseUrl)
    val ollamaBaseUrl: StateFlow<String> = _ollamaBaseUrl.asStateFlow()

    private val _miniMaxBaseUrl = MutableStateFlow(store.miniMaxBaseUrl)
    val miniMaxBaseUrl: StateFlow<String> = _miniMaxBaseUrl.asStateFlow()

    private val _miniMaxModel = MutableStateFlow(store.miniMaxModel)
    val miniMaxModel: StateFlow<String> = _miniMaxModel.asStateFlow()

    private val _wakeWord = MutableStateFlow(store.wakeWord)
    val wakeWord: StateFlow<String> = _wakeWord.asStateFlow()

    private val _voiceResponse = MutableStateFlow(store.voiceResponse)
    val voiceResponse: StateFlow<Boolean> = _voiceResponse.asStateFlow()

    private val _ttsVoiceName = MutableStateFlow(store.ttsVoiceName)
    val ttsVoiceName: StateFlow<String> = _ttsVoiceName.asStateFlow()

    private val _braveSearchApiKey = MutableStateFlow(store.braveSearchApiKey)
    val braveSearchApiKey: StateFlow<String> = _braveSearchApiKey.asStateFlow()

    private val _defaultMsgChannel = MutableStateFlow(store.defaultMsgChannel)
    val defaultMsgChannel: StateFlow<String> = _defaultMsgChannel.asStateFlow()

    /** Available TTS voices on this device (populated asynchronously). */
    private val _availableVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoiceInfo>> = _availableVoices.asStateFlow()

    // Kept alive after enumeration so it can also play voice previews
    private var previewTts: TextToSpeech? = null

    // ── Speaker profiles ───────────────────────────────────────────────────────

    private val _speakerProfiles = MutableStateFlow<List<PersonRecord>>(emptyList())
    val speakerProfiles: StateFlow<List<PersonRecord>> = _speakerProfiles.asStateFlow()

    fun loadSpeakerProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            _speakerProfiles.value = speakerStore.getAllPersons()
        }
    }

    fun deleteSpeakerProfile(personId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            speakerStore.deletePerson(personId)
            _speakerProfiles.value = speakerStore.getAllPersons()
        }
    }

    // ── Memory management ──────────────────────────────────────────────────────

    /** Wipe all memory_entries rows (summaries, preferences, facts, tasks). */
    fun clearAllMemories() {
        viewModelScope.launch(Dispatchers.IO) {
            db.memoryDao().deleteAll()
            db.memoryFactDao().deleteAll()
        }
    }

    /** Wipe conversation_turns + conversation_sessions only (raw dialogue history). */
    fun clearConversationHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            db.conversationDao().deleteAllTurns()
            db.conversationDao().deleteAllSessions()
        }
    }

    init {
        enumerateVoices()
        loadSpeakerProfiles()
    }

    private fun enumerateVoices() {
        previewTts = TextToSpeech(getApplication()) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech

            // All English voices, both offline and online, sorted by locale then name
            val rawVoices = previewTts?.voices
                ?.filter { it.locale.language == "en" }
                ?.sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
                ?: emptyList()

            // Group by locale+gender so voices can be numbered meaningfully within each group
            val byGroup = rawVoices.groupBy { "${it.locale.toLanguageTag()}|${detectGender(it) ?: "?"}" }

            val deviceVoices = rawVoices.map { voice ->
                val groupKey = "${voice.locale.toLanguageTag()}|${detectGender(voice) ?: "?"}"
                val group    = byGroup[groupKey] ?: listOf(voice)
                val idxInGroup = group.indexOf(voice)
                TtsVoiceInfo(
                    name        = voice.name,
                    displayName = friendlyVoiceName(voice, idxInGroup, group.size),
                    isNetwork   = voice.isNetworkConnectionRequired
                )
            }

            _availableVoices.value = deviceVoices
            // previewTts kept alive for previewVoice()
        }
    }

    /**
     * Speak a short sample phrase with [voiceName] so the user can audition it.
     * For the local Piper voice, routes the request through [JarvisService] so the
     * real TTS engine (including the ONNX model) is used.
     *
     * Suppresses JarvisService wake detection for the duration of playback so the
     * sample audio doesn't accidentally trigger the voice pipeline.
     */
    fun previewVoice(voiceName: String) {
        val tts   = previewTts ?: return
        val voice = tts.voices?.find { it.name == voiceName } ?: return
        tts.voice = voice

        val app = getApplication<Application>()

        // Pause wake-word detection so the sample doesn't trigger Jarvis
        JarvisService.suppressWake(app)

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                JarvisService.restoreWake(app)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                JarvisService.restoreWake(app)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                JarvisService.restoreWake(app)
            }
        })

        tts.speak(
            "Hi, I'm Jarvis. This is how I sound.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "jarvis_preview"
        )
    }

    /**
     * Try to infer gender from the voice's internal name.
     *
     * Covers Samsung TTS (`en-us-x-samf1-local`, `en-us-x-samm1-local`),
     * voices with explicit "male"/"female" substrings, and some OEM patterns
     * (e.g. `-f-`, `-m-` separators).  Returns null when undetermined.
     */
    private fun detectGender(voice: Voice): String? {
        val n = voice.name.lowercase()
        return when {
            "female" in n || "woman" in n || "girl" in n         -> "Female"
            ("male" in n && "female" !in n) || "man" in n        -> "Male"
            // Samsung: en-us-x-samf1-local / en-us-x-samm1-local
            Regex("""-samf\d+-""").containsMatchIn(n)            -> "Female"
            Regex("""-samm\d+-""").containsMatchIn(n)            -> "Male"
            // Generic OEM: -f1-, -f2-, -m1-, -m2- separators
            Regex("""-f\d+-""").containsMatchIn(n)               -> "Female"
            Regex("""-m\d+-""").containsMatchIn(n)               -> "Male"
            // voices whose 3-letter ID ends in 'f' (common Google pattern)
            Regex("""-x-[a-z]{2}f-""").containsMatchIn(n)       -> "Female"
            Regex("""-x-[a-z]{2}m-""").containsMatchIn(n)       -> "Male"
            else                                                   -> null
        }
    }

    /** Build a human-friendly label from an Android [Voice] object. */
    private fun friendlyVoiceName(voice: Voice, idxInGroup: Int, totalInGroup: Int): String {
        val accentLabel = accentName(voice.locale)
        val gender  = detectGender(voice)
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "HD"
            voice.quality >= Voice.QUALITY_HIGH      -> "HQ"
            else                                      -> null
        }
        val network = if (voice.isNetworkConnectionRequired) "Online" else null
        val index   = if (totalInGroup > 1) "#${idxInGroup + 1}" else null

        return listOfNotNull(accentLabel, gender, quality, network, index).joinToString(" · ")
    }

    /**
     * Map a locale to a concise, human-friendly accent name.
     * Falls back to `"Language (Country)"` for unlisted locales.
     */
    private fun accentName(locale: Locale): String = when (locale.toLanguageTag()) {
        "en-US", "en"   -> "American English"
        "en-GB"          -> "British English"
        "en-AU"          -> "Australian English"
        "en-IE"          -> "Irish English"
        "en-IN"          -> "Indian English"
        "en-CA"          -> "Canadian English"
        "en-ZA"          -> "South African English"
        "en-NG"          -> "Nigerian English"
        "en-NZ"          -> "New Zealand English"
        "en-PH"          -> "Filipino English"
        "en-SG"          -> "Singaporean English"
        "en-HK"          -> "Hong Kong English"
        "en-KE"          -> "Kenyan English"
        "en-TZ"          -> "Tanzanian English"
        "en-GH"          -> "Ghanaian English"
        else -> {
            val lang    = locale.getDisplayLanguage(Locale.ENGLISH).trim()
            val country = locale.getDisplayCountry(Locale.ENGLISH).trim()
            if (country.isNotBlank() && !country.equals(lang, ignoreCase = true))
                "$lang ($country)" else lang
        }
    }

    override fun onCleared() {
        super.onCleared()
        previewTts?.shutdown()
        previewTts = null
    }

    // ── OpenAI OAuth state ─────────────────────────────────────────────────

    private val _openAiClientId = MutableStateFlow(store.openAiClientId)
    val openAiClientId: StateFlow<String> = _openAiClientId.asStateFlow()

    /** True once an access token is stored. */
    private val _openAiSignedIn = MutableStateFlow(
        store.openAiOAuthEnabled && store.openAiAccessToken.isNotBlank()
    )
    val openAiSignedIn: StateFlow<Boolean> = _openAiSignedIn.asStateFlow()

    private val _openAiOAuthError = MutableStateFlow<String?>(null)
    val openAiOAuthError: StateFlow<String?> = _openAiOAuthError.asStateFlow()

    // ── Mutators ───────────────────────────────────────────────────────────

    fun setLlmProvider(v: String) { _llmProvider.value = v; store.llmProvider = v }
    fun setApiKey(v: String)       { _apiKey.value = v;       store.apiKey = v }
    fun setOllamaBaseUrl(v: String){ _ollamaBaseUrl.value = v; store.ollamaBaseUrl = v }
    fun setMiniMaxBaseUrl(v: String){ _miniMaxBaseUrl.value = v; store.miniMaxBaseUrl = v }
    fun setMiniMaxModel(v: String) { _miniMaxModel.value = v; store.miniMaxModel = v }
    fun setWakeWord(v: String)     { _wakeWord.value = v;     store.wakeWord = v }
    fun setVoiceResponse(v: Boolean){ _voiceResponse.value = v; store.voiceResponse = v }
    fun setTtsVoiceName(v: String) {
        _ttsVoiceName.value = v
        store.ttsVoiceName  = v
        // Push the change to the running service so it takes effect without a restart
        if (JarvisService.isRunning(getApplication())) {
            JarvisService.applyVoice(getApplication(), v)
        }
    }
    fun setBraveSearchApiKey(v: String)  { _braveSearchApiKey.value = v; store.braveSearchApiKey = v }
    fun setDefaultMsgChannel(v: String)  { _defaultMsgChannel.value = v; store.defaultMsgChannel = v }
    fun setOpenAiClientId(v: String){ _openAiClientId.value = v; store.openAiClientId = v }

    // ── OpenAI OAuth ───────────────────────────────────────────────────────

    /**
     * Generate a PKCE challenge and return the URI to open in the browser.
     * Returns null if no client_id has been entered.
     *
     * Also registers a one-shot callback in OAuthCallbackHolder so that when
     * MainActivity receives the redirect it can hand the code back here.
     */
    fun buildSignInUri(): Uri? {
        val clientId = store.openAiClientId.trim()
        if (clientId.isBlank()) {
            _openAiOAuthError.value = "Enter your OpenAI client ID first."
            return null
        }

        val verifier   = OpenAiOAuthManager.generateCodeVerifier()
        val challenge  = OpenAiOAuthManager.generateCodeChallenge(verifier)
        val state      = UUID.randomUUID().toString()

        // Persist verifier so it survives across process restart (unlikely but safe)
        store.openAiCodeVerifier = verifier

        // Register callback — called by MainActivity when the redirect arrives
        OAuthCallbackHolder.pendingCallback = { code ->
            handleOAuthCode(code, verifier, clientId)
        }

        _openAiOAuthError.value = null
        return OpenAiOAuthManager.buildAuthUri(clientId, challenge, state)
    }

    private fun handleOAuthCode(code: String, verifier: String, clientId: String) {
        viewModelScope.launch {
            try {
                val (access, refresh) = OpenAiOAuthManager.exchangeCode(code, verifier, clientId)
                store.openAiAccessToken  = access
                store.openAiRefreshToken = refresh
                store.openAiOAuthEnabled = true
                store.openAiCodeVerifier = ""   // clear temporary value
                _openAiSignedIn.value = true
                _openAiOAuthError.value = null
            } catch (e: Exception) {
                _openAiOAuthError.value = "Sign-in failed: ${e.message}"
            }
        }
    }

    fun signOutOpenAi() {
        store.clearOpenAiOAuth()
        _openAiSignedIn.value = false
        _openAiOAuthError.value = null
    }

    // ── OpenClaw state ─────────────────────────────────────────────────────

    private val _openClawEnabled    = MutableStateFlow(store.openClawEnabled)
    val openClawEnabled: StateFlow<Boolean> = _openClawEnabled.asStateFlow()

    private val _openClawHost       = MutableStateFlow(store.openClawHost)
    val openClawHost: StateFlow<String> = _openClawHost.asStateFlow()

    private val _openClawPort       = MutableStateFlow(store.openClawPort.toString())
    val openClawPort: StateFlow<String> = _openClawPort.asStateFlow()

    private val _openClawSecure     = MutableStateFlow(store.openClawSecure)
    val openClawSecure: StateFlow<Boolean> = _openClawSecure.asStateFlow()

    private val _openClawAuthToken  = MutableStateFlow(store.openClawAuthToken)
    val openClawAuthToken: StateFlow<String> = _openClawAuthToken.asStateFlow()

    private val _openClawTimeoutMs  = MutableStateFlow(store.openClawTimeoutMs.toString())
    val openClawTimeoutMs: StateFlow<String> = _openClawTimeoutMs.asStateFlow()

    private val _openClawStatus = MutableStateFlow(OpenClawConnectionStatus.NOT_CONFIGURED)
    val openClawConnectionStatus: StateFlow<OpenClawConnectionStatus> = _openClawStatus.asStateFlow()

    // ── OpenClaw mutators ──────────────────────────────────────────────────

    fun setOpenClawEnabled(v: Boolean) {
        _openClawEnabled.value = v
        store.openClawEnabled = v
        if (!v) _openClawStatus.value = OpenClawConnectionStatus.NOT_CONFIGURED
    }

    fun setOpenClawHost(v: String) {
        _openClawHost.value = v
        store.openClawHost = v
        _openClawStatus.value = OpenClawConnectionStatus.NOT_CONFIGURED
    }

    fun setOpenClawPort(v: String) {
        _openClawPort.value = v
        v.toIntOrNull()?.let { store.openClawPort = it }
        _openClawStatus.value = OpenClawConnectionStatus.NOT_CONFIGURED
    }

    fun setOpenClawSecure(v: Boolean) {
        _openClawSecure.value = v
        store.openClawSecure = v
        _openClawStatus.value = OpenClawConnectionStatus.NOT_CONFIGURED
    }

    fun setOpenClawAuthToken(v: String) {
        _openClawAuthToken.value = v
        store.openClawAuthToken = v
        _openClawStatus.value = OpenClawConnectionStatus.NOT_CONFIGURED
    }

    fun setOpenClawTimeoutMs(v: String) {
        _openClawTimeoutMs.value = v
        v.toLongOrNull()?.let { store.openClawTimeoutMs = it }
    }

    /**
     * Two-stage connection test:
     *   1. HTTP GET /health  — confirms the server is up and reachable.
     *   2. WebSocket /gateway — sends a "__ping__" transcript to prove the WS
     *      transport works end-to-end (any non-unreachable response counts as pass).
     */
    fun testOpenClawConnection() {
        _openClawStatus.value = OpenClawConnectionStatus.CONNECTING
        viewModelScope.launch {
            val settings = openClawRepo.snapshot()

            // Stage 1 — HTTP health check
            val healthStatus = OpenClawHealthMonitor.check(settings)
            if (healthStatus != OpenClawConnectionStatus.CONNECTED) {
                _openClawStatus.value = healthStatus
                return@launch
            }

            // Stage 2 — WebSocket round-trip
            val pingRequest = OpenClawRequest(
                requestId      = "ping_${System.currentTimeMillis()}",
                transcript     = "__ping__",
                routeType      = RouteType.REMOTE_FAST,
                sessionId      = "settings_test",
                timeoutMs      = settings.timeoutMs,
                isVoiceRequest = false,
            )
            val wsResult = OpenClawClient().send(settings, pingRequest)
            _openClawStatus.value = when (wsResult) {
                is OpenClawExecutionResult.Success -> OpenClawConnectionStatus.CONNECTED
                is OpenClawExecutionResult.Failure -> when (wsResult.error) {
                    is OpenClawError.AuthFailed  -> OpenClawConnectionStatus.AUTH_FAILED
                    is OpenClawError.TimedOut    -> OpenClawConnectionStatus.TIMED_OUT
                    is OpenClawError.Unreachable -> OpenClawConnectionStatus.UNREACHABLE
                    // TaskFailed / MalformedResponse / ConnectionDropped — WS reached the
                    // server fine, it just didn't recognise the ping payload. Still a pass.
                    else                         -> OpenClawConnectionStatus.CONNECTED
                }
                else -> OpenClawConnectionStatus.CONNECTED
            }
        }
    }
}
