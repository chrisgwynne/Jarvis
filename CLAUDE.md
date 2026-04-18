# CLAUDE.md

Notes for Claude on the natural-conversation / presence work landed on
`claude/jarvis-natural-conversation-uj2EY`.  Keep this file tight — it's a
navigation aid, not a full spec.

## Voice and tone

One voice across every surface (chat, action confirmations, proactive
output, follow-ups, errors): calm, observant, direct, understated,
quietly confident.  Banned phrasing lives in the system prompt — don't
let tool confirmations or proactive strings reintroduce it.

Key files:

- `app/src/main/java/com/jarvis/assistant/prompt/PromptAssembler.kt` —
  main system prompt. Sections: IDENTITY, CORE BEHAVIOUR, BANNED PHRASES,
  RESPONSE LENGTH, SMALL-TALK EXAMPLES, TOOL USAGE, MEMORY, PROACTIVE
  OUTPUT, FAILURE, PRESENCE.
- `app/src/main/java/com/jarvis/assistant/data/ConversationStore.kt` —
  fallback system prompt. Keep in sync with PromptAssembler's defaults.
- `app/src/main/java/com/jarvis/assistant/tools/device/AnalyzeCameraViewTool.kt`
  — camera-vision system message.  Same tone rules.

Runtime safety net: `ResponseFormatter` caps at 3 sentences / 320 chars
and strips markdown.  Don't rely on the prompt alone.

## App launching

Five-tier resolution order — don't skip, don't fail early:

1. Learned alias (`AppAliasStore` SharedPreferences)
2. Built-in alias map (hard-coded in `AppResolver`)
3. Installed-app label match
4. Package-name segment match
5. Category intent fallback

Files:

- `app/src/main/java/com/jarvis/assistant/tools/device/AppResolver.kt` —
  resolution logic; `Launchable` carries a `Confidence` (HIGH/LOW).
- `app/src/main/java/com/jarvis/assistant/tools/device/OpenAppTool.kt` —
  LOW-confidence hits ask "Did you mean X?" once. Confirmation matching
  is deliberately anchored and capped at 4 words so "right now, open X"
  isn't swallowed as a "yes".
- `app/src/main/java/com/jarvis/assistant/tools/device/AppAliasStore.kt`
  — persistent learned map.

When the user confirms, the alias is persisted under the spoken form so
future calls hit tier 1.

## Interruption and resume

- Barge-in detection: `audio/BargeInDetector.kt`.
- Classification: `conversation/InterruptionClassifier.kt`
  (URGENT / CORRECTION / CONTINUE / CLARIFICATION / REPLACEMENT / UNRELATED).
- Resume: `JarvisRuntime.resumeContinuation` — re-invokes the LLM with
  what was actually spoken spliced in as the last assistant turn plus a
  short directive. Does **not** replay the unspoken tail verbatim.

Default behaviour is **do not resume**.  Only CONTINUE (explicit user
signal) leads back; CLARIFICATION keeps state alive for a subsequent
"go on".  Everything else discards.

Resume also rewrites `ConversationStore` so the stored history has one
merged assistant turn (`spokenSoFar + resume`) rather than two
back-to-back entries.  Uses `dropLastAssistant()` before the stream and
`replaceLastAssistant(merged)` after.

## Proactive engine

Pipeline: `EventGenerator` → `EventScorer` → `DecisionEngine` →
`TtsProactiveDispatcher`.  Policy layers (see `InterruptLevel`,
`ProactiveConfig` docs):

- L0 silent awareness — no surfaced action (the common case).
- L1 passive context — state adjusts, no output.
- L2 soft suggestion — `InterruptLevel.PASSIVE` (notification).
- L3 contextual assistance — `InterruptLevel.ACTIVE`, tied to a moment.
- L4 active intervention — critical events (low battery, imminent
  reminder).  Always passes gates.

Gates applied in `DecisionEngine.decide`:

1. Stale + NONE filter
2. Empty guard
3. Global gap (`minGlobalGapMs`, 60 s default)
3b. Quiet hours — wraps midnight; enabled 22:00–07:00 in
    `JarvisRuntime`, defaulted to null in `ProactiveConfig` so tests
    are stable.
3c. Presence gate — defers PASSIVE when user is mid-conversation /
    driving / winding down / late night.
4. Top candidate + action mapping.

### Adaptation

`CooldownStore` tracks per-key ignore/accept.  `EventScorer` stretches
the effective cooldown by `(1 + ignoreCount × factor)`, capped at 5
steps so a permanently-ignored key doesn't balloon indefinitely.

`ProactiveEngine` records a `PendingVerdict` after each dispatch;
resolved on the next tick (user interacted → accepted, else age ≥
`ignoreCheckDelayMs` → ignored).  A displaced unresolved verdict is
counted as ignored when a new dispatch overwrites it.

### Strings

All user-facing proactive strings live in `EventGenerator` (battery,
reminder, missed call, notification, behavioural learning) and
`followup/ConversationalProactiveEngine.GAP_CHECK_INS`.  Match the
chat voice — short, direct, no alert-speak.

## Presence

`context/Presence.kt` — pure value type with `TimePhase` + `ActivityMode`
+ `minutesSinceInteraction`.  Computed on demand (no long-lived
state).  Consumers:

- `ContextEngine.toPromptFragment(ctx, presence)` appends a
  "Current moment: evening, user winding down." line to the system
  prompt.  `PromptAssembler.assemble(..., presence = ...)` takes it as
  an optional parameter.  `JarvisRuntime.currentPresence()` wires it
  into all three call sites.
- `DecisionEngine` (step 3c) uses `Presence.allowsSoftSuggestions()` to
  defer PASSIVE suggestions.

`isDriving` flows through `ContextSnapshot.isDriving` (default false)
populated by `ProactiveEngine`'s `isDrivingProvider` lambda.
`JarvisRuntime` passes `drivingModeManager::isDriving`; tests leave it
at default.

The LLM is told to let presence shape tone silently — never cite the
fragment.
