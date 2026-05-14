# Code Duplication Audit

## Method

Scan `app/src/main/**/*.kt` for the architectural anchor classes that
the project's invariants require to be unique:

| Anchor | Owner |
|---|---|
| `TranscriptNormalizer` | `voice.routing` |
| `RouterPipeline` (logical) | composed in `voice.routing.LocalFirstRouter` + `JarvisRuntime` |
| `RecentContextEngine` | `runtime.context.RecentActionContextStore` |
| `UserSafeErrorHandler` | `core.safety` |
| `SessionContinuationPolicy` | `runtime.session` |
| `CommandPermissionPolicy` | `speaker.trust` |
| `ProactivitySettings` | `proactive.settings` |
| `ProactivityGate` | `proactive.settings` |
| `ContextualFollowupParser` | `runtime.context` |

## Result at baseline

Every anchor appears **exactly once** in the source tree.  There are no
duplicate routers, parsers, or policy objects in
`app/src/main/java/com/jarvis/assistant/`.

```
1 TranscriptNormalizer
1 RecentActionContextStore
1 UserSafeErrorHandler
1 SessionContinuationPolicy
1 CommandPermissionPolicy
1 ContextualFollowupParser
1 ProactivityGate
1 ProactivitySettings
```

There are no duplicate basenames either — `find app/src -name "*.kt" |
uniq -d` returns empty.

## Routing order (verified)

`LocalFirstRouter` and the pre-route layers in `JarvisRuntime` apply
the following order, which matches the sprint spec:

1. Stop / silence commands (handled by `JarvisRuntime` pre-loop).
2. `TranscriptNormalizer.rewrite(...)`
3. `ContextualFollowupResolver.resolve(...)` (`runtime.context`)
4. Todoist intercepts (`todoist.TodoistReminderRouter`)
5. `InstantCommandRouter` (phone-capable intents)
6. `AttentionGate`
7. `LocalFirstRouter` → `ToolRegistry.match()`
8. OpenClaw (gated behind `openClawEnabled` + explicit phrasing)
9. LLM fallback

This matches the desired final order in the cleanup brief.

## Specific dedupe checks

| Suspected duplication | Verdict |
|---|---|
| Two TranscriptNormalizers | Not found. |
| Old "OpenClaw-first" routing | Not found in current tree. |
| Duplicate Todoist parsers | `TodoistListQueryParser`, `ReminderIntentParser`, `DateTimeExpressionParser`, `ConversationalEditParser` each have a single, distinct responsibility — not duplicates. |
| Duplicate Calendar parsers | `CalendarTool` (read) and `CalendarCreateTool` (write) are the only two — distinct verbs. |
| Duplicate error handlers | One `UserSafeErrorHandler`; `SpeechSanitizer` complements it (separate concern: TTS leak filtering). |

## Recommended follow-ups (separate PRs)

- **`JarvisRuntime.kt` is 237 KB.** It's a single-responsibility
  orchestrator but big enough to make readability suffer. Suggested
  extraction targets:
  - `JarvisRuntimeFactory` (initialize() body → builders)
  - `JarvisRuntimeReminderHandlers` (the `onReminderTriggered` /
    `speakLocationReminder` / `speakProactivityTest` cluster)
  - `JarvisRuntimeLifecycle` (start / stop / suppressWake /
    restoreWake)
- **Move-package refactor** to the proposed `routing/`, `commands/`,
  `phone/`, `apps/`, `messaging/`, `todoist/`, … tree. Mechanical
  rename only — keep behaviour identical.
- **Add `:app:checkArchitectureInvariants` Gradle task** that fails the
  build if any of the named singleton classes grows a second
  definition.
