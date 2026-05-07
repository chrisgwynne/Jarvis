# Latency Budget

Phase 4 target: **< 400 ms** from end-of-speech to first audible TTS token.

This file is the working definition of "what each pipeline stage gets to
spend" so a regression in one stage doesn't get masked by slack in
another.  `LatencyTracker` enforces the first-token budget at runtime
(`TARGET_FIRST_TOKEN_MS = 400`) and logs a `[BUDGET_BREACH]` warning when
a turn exceeds it.

## End-of-speech → first audible token (target ≤ 400 ms)

| Stage                       | Budget   | Owner                              |
|-----------------------------|----------|------------------------------------|
| STT finalisation (`STT_COMPLETE` after `EndOfSpeech`) | 30 ms | `SpeechCapture` + Android `SpeechRecognizer` |
| Intent / tool match         | 20 ms    | `KeywordIntentRouter`, `ToolRegistry.match` |
| Memory + context retrieval  | 50 ms    | `MemoryRetriever`, `KnowledgeQueryEngine` |
| LLM request → first token   | **220 ms** | `LlmRouter.streamWithMessages`, network RTT |
| TTS first frame             | 80 ms    | `TtsEngine.speak` (Android TTS)    |

Headroom: 0 ms — the budget is already tight.  Any new stage added to the
hot path needs an explicit owner and a justification in this table.

## Per-stage notes

### LLM (220 ms)

The hardest stage.  Strategies in use:

* `LlmRouter.streamWithMessages` emits per-sentence so TTS starts on the
  first sentence boundary, not the full response.
* `NetworkClient.prewarm` is fired on `JarvisRuntime.start()` so the DNS
  + TLS handshake to the active provider is paid up-front (~150–400 ms
  saving on a cold radio).
* HTTP/2 explicit + 10-min keep-alive in `NetworkClient` so back-to-back
  turns reuse the TLS session (saves ~80 ms each).
* Provider object is cached by `LlmRouter` keyed on the eight settings
  the constructor reads — no per-turn allocation.

Things that breach the budget:

* Cold radio + cellular network.  Pre-warm helps but can't beat physics.
* Very long conversation context.  `ConversationCompressor` exists but
  doesn't run aggressively enough — see audit `P-5`.
* Tool-calling round trips (model → tool → model) are inherently
  serial; `MAX_TOOL_HOPS = 3` caps the worst case.

### STT (30 ms)

Android's `SpeechRecognizer` end-of-speech timing is configured in
`SpeechCapture.kt`:

* `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` = 2000
* `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS` = 1000

These are end-of-speech *trigger* delays, not the budget — the budget is
the time from `EndOfSpeech` callback to the recogniser returning a
result, which is typically < 30 ms on-device.

Cloud STT is forbidden in the budget — the network round trip alone
breaks it.  `EXTRA_PREFER_OFFLINE = true` is set; on Android 12+ we use
`createOnDeviceSpeechRecognizer` when available.

### TTS (80 ms)

Android `TextToSpeech` has unavoidable engine warm-up on the first
utterance after process start.  Mitigation: `TtsEngine` is constructed
during `JarvisRuntime.initialize()` so `onInit` has typically fired
before the first wake word.

Future work: streaming TTS providers (ElevenLabs, OpenAI streaming TTS)
would let us start playback before the LLM stream completes — currently
the per-sentence emission already covers that.

## Cold start (target ≤ 1.5 s to interactive)

Measured by the OS, not by `LatencyTracker`.  Improvements landed:

* App Startup library is **not** in use yet — `JarvisRuntime` constructor
  cost is paid on the IO dispatcher inside `JarvisService.onCreate`.
* R8 full mode is explicit (`gradle.properties` →
  `android.enableR8.fullMode=true`).
* Resource shrinking is on (`isShrinkResources = true`).
* `androidx.profileinstaller` is on the classpath; the baseline profile
  in `app/src/main/baseline-profiles.txt` is hand-curated and should be
  regenerated after major refactors.

## Measuring locally

Filter for the latency tag:

```
adb logcat -s JarvisLatency
```

Each pipeline run prints both cumulative and per-stage timings:

```
[PIPELINE_START] t=0ms
[WAKE_DETECTED] total=12ms stage=12ms
[STT_COMPLETE] total=842ms stage=830ms
[LLM_FIRST_TOKEN] total=1180ms stage=338ms
[BUDGET_BREACH] LLM_FIRST_TOKEN 1180ms exceeds 400ms target
```

Stage durations make regressions trivially attributable — you can see
exactly which step blew the budget rather than chasing a cumulative
number.
