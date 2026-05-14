# JarvisRuntime Split — Plan & Status

`JarvisRuntime` is the orchestrator that owns every long-lived
subsystem: state machine, TTS engine, speech capture, wake detector,
tool registry, proactive engine, brain engine, OpenClaw node, call
monitor, audio focus, etc. It works, but at **4,176 lines** it's the
single biggest readability + test-isolation pain point in the
codebase.

This document is the plan to split it into focused controllers
without changing behaviour. It tracks what's landed vs deferred so
follow-up PRs have a clear map.

## Target controllers

| Controller | Responsibility | Status |
|---|---|---|
| `TtsResponseController` | Diagnostics + sample-voice TTS (`testSpeak`, `speakProactivityTest`, `dispatchProactivityGateTest`). | ✅ **Extracted** (this PR) |
| `SpeechSessionController` | Pipeline TTS path (`streamAndSpeak`, `speakAndRecord`, `speakLocationReminder`). Touches audio focus, wake detector, state machine, BT-SCO. | ⏳ Deferred — couples deeply to state machine + barge-in. Needs its own PR with a well-scoped audio-focus interface. |
| `LocalCommandExecutionController` | Tool registry dispatch + `SessionContinuationPolicy` + last-action recording. | ⏳ Deferred — top of the pipeline; touches every other controller. Land after the easier extractions. |
| `RemoteRoutingController` | OpenClaw dispatch + gating. Logs `[INVALID_REMOTE_ROUTE]` when phone-capable transcripts try to escalate. | ⏳ Deferred — small surface area, good candidate for the next PR. |
| `ProactivityRuntimeController` | Lifecycle of `ProactiveEngine` + `ConversationalProactiveEngine` + `ScheduledReminderEngine` + their dispatchers. | ⏳ Deferred — needs careful construction-order plumbing. |
| `SessionStateController` | Wraps `JarvisStateMachine`, `sessionId`, session open/close, conversation flush. | ⏳ Deferred — touched by every other controller; do last so the API has settled. |
| `JarvisRuntimeCoordinator` | Outer facade. Owns the controllers, exposes the existing public API, stays under ~500 lines. | ⏳ Deferred — emerges naturally as the other controllers move out. |
| `ErrorRecoveryController` | Funnels every catch-block through `UserSafeErrorHandler` + structured logs. | ⏳ Deferred — wait until call sites are extracted into controllers; touching them in place would double the work. |

## Why the staged plan

1. **Behaviour preservation is the contract.** The user has zero
   tolerance for routing regressions. Each extraction must be a
   pure-mechanical "move methods + wire deps" change, then a build,
   then a test run.
2. **The controllers reference each other** through state owned by
   `JarvisRuntime` (state machine, wake detector, audio focus). The
   only safe order is "outermost first" — extract leaves of the
   dependency graph (diagnostics, scheduled reminders), then move
   inward.
3. **Each PR should fail loud if it breaks anything.** That's the
   whole point of `:app:checkArchitectureInvariants` — drift between
   PRs is automatically surfaced.

## How a controller is extracted

The `TtsResponseController` pattern that landed in this PR is the
template for every future one:

1. Identify a *coherent* method cluster on `JarvisRuntime` (here:
   the three diagnostics TTS methods).
2. Create `runtime/controllers/XxxController.kt`. Inject every
   external dependency as a constructor arg or lambda; do **not**
   take a reference to `JarvisRuntime`.
3. Copy the methods verbatim into the controller. Adjust only the
   names that move from `this.foo` to a constructor-injected `foo`.
4. On `JarvisRuntime`, replace each method body with a one-line
   delegate to the controller. Keep the same signature, same
   KDoc.
5. Build + test. No semantic change has occurred, so every existing
   test passes unmodified.
6. Add `runtime/controllers/XxxControllerTest.kt` exercising the
   controller directly with stub lambdas — for the first time these
   methods are unit-testable without spinning up the whole runtime.

This template is small, boring, and safe. That's the point.

## What's deliberately NOT in this PR

- No package moves of existing files (e.g. `tools.device → commands`).
  Mechanical rename PRs deserve their own focused review.
- No behaviour changes. Every public method on `JarvisRuntime` keeps
  the same signature and the same observable effect.
- No new logging beyond what the controller already had on the
  runtime. The `[PROACTIVITY_TEST_SPEAK_*]` markers move with the
  code unchanged.

## Architecture invariants (enforced automatically)

See `docs/architecture/routing-invariants.md` for the full rule list.
Every rule is a unit test under
`app/src/test/java/com/jarvis/assistant/architecture/`. Wired into
the Gradle task `:app:checkArchitectureInvariants`, which depends on
`:app:testDebugUnitTest`.

Run locally before pushing:

```
./gradlew :app:checkArchitectureInvariants
```

Failing the task means a routing or extraction rule has been broken.
The test output points at the file and reason; fix the violation,
don't disable the test.
