# Architecture

This document is the operating model of the Jarvis Android app — what owns
what, how data flows, and where to look when a behaviour surprises you.
For the detailed audit (bugs, code smells, dependency CVEs) see
[`JARVIS_AUDIT.md`](../JARVIS_AUDIT.md).

## Module layout

Single-module Android app at `:app`.  Every package lives under
`com.jarvis.assistant.*`.

| Package           | Owns                                                       |
|-------------------|------------------------------------------------------------|
| `service.*`       | `JarvisService`, `BootReceiver`, screening + listener services |
| `runtime.*`       | `JarvisRuntime` (orchestrator), `VoicePipeline`, `ToolDispatcher`, `OfflineManager`, `PlanRunner` |
| `audio.*`         | Wake-word detectors, `SpeechCapture`, `TtsEngine`, `BargeInDetector`, BT SCO + audio focus |
| `llm.*`           | `LlmRouter`, `NetworkClient`, providers (`Anthropic`, `OpenAi`, `Hermes`, …) |
| `memory.*`        | Room DB schema, `MemoryWriter`, `MemoryRetriever`, `ProfileMemoryService` |
| `knowledge.*`     | Wiki + facts compiler, query engine, retention policy      |
| `core.decisions.*`| `TriggerEngine`, `DecisionBrain`, action ledger, situation registry |
| `core.events.*`   | `EventBus`, adapters (battery, network, telephony, BT, foreground app) |
| `proactive.*`     | `ProactiveEngine`, scorers, cooldown store, conversational follow-up |
| `tools.*`         | 40+ device / web / smart-home tools registered into `ToolRegistry` |
| `remote.openclaw.*` | Self-hosted OpenClaw client (HTTP, OpenAI-compatible)    |
| `remote.hermes.*` | Self-hosted Hermes Agent jobs client                       |
| `accessibility.*` | Screen-context inspector + actuator (opt-in)              |
| `ui.*`            | Compose surfaces (`MainScreen`, settings, orb, waveform)   |
| `ui.theme.*`      | Tokens, palette, typography, `JarvisTheme` (Phase 2)       |
| `security.*`      | Action-policy gate, app lock, storage policy               |
| `reporting.github.*` | Auto-issue reporter (owner/dev mode, scrubbed)          |

## Process model

A single foreground service (`JarvisService`) owns the entire runtime.  Its
lifetime is the lifetime of "Jarvis is listening."

```
BootReceiver / MainActivity
        │ startForegroundService(ACTION_START)
        ▼
JarvisService.onCreate ───► spins JarvisRuntime on Dispatchers.IO
                                  │
                                  ├── start()  : begins audio + adapters
                                  ├── stop()   : idempotent teardown
                                  └── public API for ACTION_* intents
```

`JarvisRuntime` is currently a god object (~2.5k LOC, ~100 imports) — see
audit `SM-1`.  Splitting it into smaller coordinators is on the roadmap;
do not add new responsibilities to it.

## Voice pipeline

```
WakeWordDetector
   │ onDetected (haptic tick + chime)
   ▼
JarvisStateMachine: WakeDetected → Listening
   │ SpeechCapture.listen()
   ▼
JarvisStateMachine: Processing
   │ FollowUpCoordinator → IntentClassifier → ToolRegistry
   │   (or LlmRouter.streamWithMessages for free-form replies)
   ▼
TtsEngine.speak() ── interruptible by BargeInDetector
   │
   ▼
JarvisStateMachine: IdleWake → loop
```

Latency budget is documented separately in
[`LATENCY_BUDGET.md`](LATENCY_BUDGET.md).  `LatencyTracker` logs every
stage to `JarvisLatency` in logcat.

## LLM routing

`LlmRouter` is a single-instance dispatcher held by `JarvisRuntime`.  It
caches the active provider keyed on the eight settings the provider
constructor reads, so back-to-back turns reuse one provider instance.

Providers implement a small interface (`complete`, `streamComplete`, plus
optional `completeWithTools`).  All public-cloud providers are HTTPS and
ignore the network-security config; the OpenClaw and Hermes providers can
also be configured for HTTP on a private LAN (cleartext is permitted by
default — see `network_security_config.xml`).

| Provider     | Wire format             | Notes                          |
|--------------|-------------------------|--------------------------------|
| OpenAI       | OpenAI                  | OAuth optional via PKCE flow   |
| Anthropic    | Anthropic Messages      | Function calling supported      |
| Gemini       | Gemini generateContent  | Function calling supported      |
| OpenRouter   | OpenAI-compatible       | Adds `HTTP-Referer` header     |
| Kimi         | OpenAI-compatible       | Moonshot endpoint              |
| MiniMax      | OpenAI-compatible       | User-configurable base URL     |
| Ollama       | Ollama native           | Self-hosted, no auth           |
| **Hermes**   | OpenAI-compatible       | Self-hosted, Bearer auth       |

## Persistence

Two stores:

* **EncryptedSharedPreferences** (`SettingsStore`) — API keys, OAuth
  tokens, user-tunable settings.  AES256-GCM via Android Keystore.
* **Room DB** (`JarvisDatabase`, `jarvis.db`) — memory entries,
  conversation turns, knowledge wiki, telemetry, goals, brain events.
  WAL journal mode (Phase 1 fix `P-4`).

Conversation history in Room is **not** encrypted at rest.  Users with
threat models that require it should disable conversation persistence in
Settings → Privacy.

## Auto-issue reporting

Crashes and uncaught coroutine failures in the major subsystems are
auto-reported to GitHub by `IssueReporter` (rate-limited + scrubbed for
secrets).  See [`AUTO_REPORTING.md`](AUTO_REPORTING.md) for the source
table, gates, and how to add a new reporter.

## Service-killed detection

`MainScreen` shows a `ServiceHealthBanner` above the conversation when
battery optimisation is not exempted.  Tapping deep-links to
[dontkillmyapp.com](https://dontkillmyapp.com) for OEM-specific guidance.
That's a UX hint, not a hard guard — Samsung / Xiaomi / Huawei still
require additional settings the OS doesn't expose programmatically.

## Where to start when …

* **Adding a new tool** — implement `Tool`, register in `ToolRegistry`,
  add an entry to the action-policy allowlist.
* **Adding a new LLM provider** — extend `BaseOpenAiProvider` if it's
  OpenAI-compatible (Hermes is the simplest example), otherwise implement
  `LlmProvider` directly.  Add to `LlmRouter.providerByName` and
  `activeProviderOrigin` for pre-warming.
* **Changing system-prompt tone** — start at
  `prompt/PromptAssembler.kt` and the small mirror in
  `data/ConversationStore.kt`.  The runtime safety net
  (`ResponseFormatter`) caps output at 3 sentences / 320 chars.
* **Adding a settings toggle** — add a key + accessor to
  `SettingsStore`, expose via `SettingsViewModel`, render in the relevant
  `ui/settings/screens/*` file.
