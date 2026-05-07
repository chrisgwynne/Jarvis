# Auto-issue reporting

Jarvis can automatically file GitHub issues when something goes wrong at
runtime — crashes, repeated LLM provider failures, OpenClaw / Hermes
auth rejections, and any uncaught coroutine exception in the major
subsystems.  This document explains where the reports come from, what
gates them, and how to configure or disable the system.

## Sources

| Source                          | Severity | Path                                           |
|---------------------------------|----------|------------------------------------------------|
| `Thread.UncaughtExceptionHandler` (full process crash) | FATAL | `JarvisUncaughtHandler` → on-disk queue → drained on next cold start |
| Uncaught coroutine exception in `JarvisRuntime.scope` | HIGH | `autoReporting("runtime")` |
| Uncaught coroutine exception in `JarvisService.serviceScope` | HIGH | `autoReporting("service")` |
| Wake-word detector loop (`TFLiteWakeWordDetector`, `GoogleWakeWordDetector`) | HIGH | `autoReporting("wake-tflite")`, `autoReporting("wake-google")` |
| Barge-in detector loop          | HIGH | `autoReporting("barge-in")` |
| Proactive engine loop           | HIGH | `autoReporting("proactive")` |
| Conversational follow-up engine | HIGH | `autoReporting("conv-proactive")` |
| Behavioural learning engine     | HIGH | `autoReporting("brain")` |
| LLM provider failure (after fallback) | HIGH | `LlmRouter.reportProviderFailure` |
| OpenClaw / Hermes auth failure  | HIGH | `OpenClawClient`, `HermesJobsClient` |
| Manual one-off escalation       | FATAL/HIGH/INFO | direct `IssueReporter.report*` call |

`LOW` and `MEDIUM` reports never reach GitHub — they're logged locally
and dropped at `IssueReporter.submit`.

## Gates (in order)

Every report passes through `IssueReporter.submitImmediate` before any
network call:

1. **Severity gate** — only HIGH and FATAL are eligible.
2. **Repetition threshold** (`IssueRateLimiter`) — HIGH reports need to
   recur N times inside a window before they file.  This is what stops
   one-off transient blips becoming GitHub noise.
3. **Per-fingerprint cooldown** — the same fingerprint can only file
   once per cooldown.  Tight crash loops collapse into a single issue.
4. **Global daily cap** — hard ceiling on issues filed per 24 hours.
5. **Feature flag** (`SettingsStore.githubReportingEnabled`).
6. **Token presence** (`SettingsStore.githubToken`) — no token, no call.

The fingerprint is the SHA-256 of `(severity, subsystem, category,
top-of-stack-trace)` — see `IssueFingerprint`.

## Scrubbing

Before any payload leaves the device:

* `CrashReportBuilder` masks tokens / API keys in stack messages with
  pattern matches (`api_key=…`, `Bearer …`, `password=…`).
* `IssueReporter.scrub` strips metadata keys whose name contains
  `token`, `secret`, `password`, `api_key`.

## Configuration

User-facing settings live in `Settings → Advanced → Issue reporting`:

| Setting                          | Default | Notes |
|----------------------------------|---------|-------|
| `githubReportingEnabled`         | false   | Master switch.  When off, every `submit` returns `DisabledByFlag`. |
| `githubRepoOwner`                | `chrisgwynne` | Owner of the target repo. |
| `githubRepoName`                 | `Jarvis` | Target repo. |
| `githubToken`                    | empty   | Personal access token with `repo` scope.  Stored in `EncryptedSharedPreferences`. |

A built-in test path (`IssueReporter.sendTestIssue`) verifies the
end-to-end pipeline against a synthetic FATAL report — used by the
"Send test issue" button in the Advanced settings screen.

For deployments that prefer a self-hosted issue tracker, point
`githubRepoOwner` / `githubRepoName` / `githubToken` at any
GitHub-API-compatible endpoint (Gitea, Forgejo).  The wire format used
by `GitHubIssueClient` is the standard `POST /repos/{owner}/{repo}/issues`
shape.

## Adding a new auto-reporter

1. Pick a short subsystem name (one word, hyphenated if needed).
2. If the subsystem owns a `CoroutineScope`, replace it with:
   ```kotlin
   private val scope = CoroutineScope(
       SupervisorJob() + Dispatchers.X +
           com.jarvis.assistant.reporting.github.autoReporting("my-subsystem")
   )
   ```
3. For caught-but-significant errors (auth failures, repeated retries
   exhausted, model contract violations), call `IssueReporter.get()
   ?.reportHigh(...)` at the catch boundary.  The rate limiter
   guarantees a transient failure won't file an issue.
4. Add a row to the table at the top of this file.

## What does **not** auto-report

* `IssueReporter.scope` itself — it can't report into itself without
  recursing.  Bugs in the reporter are log-only.
* `LOW` / `MEDIUM` severity reports — by design.
* Any caught exception that's expected as part of normal operation
  (e.g. `CancellationException`, `IOException` from a network blip).
