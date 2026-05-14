# Routing Invariants

These rules are enforced by `ArchitectureInvariantsTest`, which is
wired into the Gradle task `:app:checkArchitectureInvariants`. Any
violation fails the build.

> "We don't trust ourselves to remember these rules at 3 a.m.; we
> trust the test suite."

## Invariant R1 — Phone-capable commands never reach OpenClaw or Hermes

**Why:** the user is holding the phone. A round-trip to a remote
relay (OpenClaw / Hermes) to make a phone call, send a message, take a
photo, toggle the torch, or read the clock is *slower and less
reliable than the local path*. It also leaks intent metadata over the
wire for no benefit.

**Enforcement:** any tool name in `PhoneCapableIntents.NAMES` MUST NOT
appear as a string literal inside `remote/openclaw/**` or
`hermes/**` source files (excluding comments / log strings explicitly
flagged with `// allowlist:phone-name`).

## Invariant R2 — Todoist commands stay local-first

**Why:** Todoist is configured as a *direct* integration. Routing
"add buy milk to my reminders" through OpenClaw would make a single
local call into two network hops + an inference step + a tool call.

**Enforcement:** `todoist/**` packages do not import from
`remote/openclaw/**` or `hermes/**`. The `TodoistReminderRouter`
intercept runs *ahead* of OpenClaw in `JarvisRuntime`'s pre-loop;
this is asserted in `LocalFirstRouterTest`.

## Invariant R3 — Calendar / Maps / Messaging / Home Assistant stay local-first

**Why:** every one of these is an on-device API (CalendarContract,
Google Maps Intents, SmsManager / WhatsApp deep link, Home Assistant
HTTP). Going through a remote relay for any of them is pure latency.

**Enforcement:** packages `tools.device.CalendarTool`,
`tools.device.CalendarCreateTool`, `maps.**`,
`tools.device.messaging.**`, and `tools.smart.SmartHomeTool` MUST NOT
reference `openclaw` / `hermes` symbols. The
`ArchitectureInvariantsTest.local_first_packages_do_not_import_remote()`
test enforces this.

## Invariant R4 — TTS never receives raw stack traces

**Why:** error messages spoken aloud are a user-facing surface.
`java.net.SocketTimeoutException at okhttp3...` is hostile.

**Enforcement:** every TTS call site MUST go through one of
- `TtsEngine.speak(text)` where `text` was returned by a tool's
  `ToolResult.Success.spokenFeedback` / `Failure.spokenFeedback`, or
- `SpeechSanitizer.sanitize(...)` for arbitrary strings, or
- `UserSafeErrorHandler.friendly(...)` for exception → user-facing
  conversion.

The invariant test scans `TtsEngine.speak(` call sites and asserts
that the immediately-preceding 20 lines contain a sanitizer call OR
are inside a `tools.device.**` file (tool author owns the wording).

## Invariant R5 — Local command success returns to Listening

**Why:** after a local tool fires successfully, the user expects
Jarvis to keep listening, not bounce straight back to wake-word mode
(which forces them to say the wake word again before a follow-up).

**Enforcement:** `SessionContinuationPolicy.decide(...)` is the single
authority. Every local command call site funnels through it. The
invariant test asserts `SessionContinuationPolicy.Verdict.CONTINUE_LISTENING`
remains the default for the local-tool path (no string-literal
override in `runtime/`).

## Invariant R6 — OpenClaw / Hermes only when explicitly enabled AND explicitly requested

**Why:** remote routing has cost (latency, privacy, battery). It must
be opt-in *and* explicit on each invocation — never a silent fallback.

**Enforcement:** the OpenClaw dispatch path in `JarvisRuntime` is
gated by both `settings.openClawEnabled` AND
`OpenClawRouter.shouldRoute(...)`. The invariant test asserts both
gates exist and that there is no second OpenClaw call site that skips
either one.

## Invariant R7 — One of each architectural anchor

Every class in the following set MUST appear exactly once in
`app/src/main/java/`:

| Class | Owner package |
|---|---|
| `TranscriptNormalizer` | `voice.routing` |
| `RecentActionContextStore` | `runtime.context` |
| `UserSafeErrorHandler` | `core.safety` |
| `SessionContinuationPolicy` | `runtime.session` |
| `CommandPermissionPolicy` | `speaker.trust` |
| `ContextualFollowupParser` | `runtime.context` |
| `ProactivityGate` | `proactive.settings` |
| `ProactivitySettings` | `proactive.settings` |
| `JarvisRuntime` | `runtime` |

The invariant test scans for `class <Name>` declarations and fails the
build if any of these grows to two definitions.

---

## How to add a new invariant

1. Add the rule under a new heading in this file.
2. Add the corresponding assertion in
   `app/src/test/java/com/jarvis/assistant/architecture/ArchitectureInvariantsTest.kt`.
3. Run `./gradlew :app:checkArchitectureInvariants` locally before
   pushing.

## Bypass policy

There is no programmatic bypass. If a rule fires a false positive,
fix the rule's exclusion list — don't disable the test.
