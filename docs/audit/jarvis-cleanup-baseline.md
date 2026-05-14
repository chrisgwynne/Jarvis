# Jarvis Cleanup — Baseline Report

**Branch:** `chore/jarvis-cleanup-audit`
**Captured:** 2026-05-14
**Captured by:** automated audit pass

## 1. Headline metrics

| Metric | Value |
|---|---|
| Total files on disk (excl. `.git/`) | **8,535** |
| Source / repo-relevant files (excl. `build/`, `.gradle/`, `.idea/`) | **691** |
| Kotlin source files | **637** |
| `.gradle/` (local Gradle cache) | **33 MB** |
| `app/build/` (build outputs) | **272 MB** |
| `app/src/` (true source) | **6.3 MB** |
| `.git/` working copy | **5.0 MB** |

**Headline finding:** the "8,000+ files" pain comes almost entirely from
two transient directories — `app/build/` and `.gradle/`. Together they
hold roughly **7,800 of the 8,535 files** and **>98% of the on-disk
volume**. A `./gradlew clean` deletes them safely; they regenerate on
every build.

## 2. Source-file health

The actively-developed footprint is **modest**: 637 `.kt` files, 11
XML, 15 webp, 2 PNG, 1 txt under `app/src/`. There are no `build/`
artefacts tracked in git, and no duplicate basenames across the
Kotlin source tree.

Singleton invariants (each architectural anchor must exist exactly
once):

| Class | Definitions found |
|---|---|
| `TranscriptNormalizer` | 1 ✅ |
| `RecentActionContextStore` | 1 ✅ |
| `UserSafeErrorHandler` | 1 ✅ |
| `SessionContinuationPolicy` | 1 ✅ |
| `CommandPermissionPolicy` | 1 ✅ |
| `ContextualFollowupParser` | 1 ✅ |
| `ProactivityGate` | 1 ✅ |
| `ProactivitySettings` | 1 ✅ |

No duplicate routers or parsers detected in the source tree at this
baseline — the "one of each" architecture is intact.

## 3. Largest tracked files

| Size | Path | Verdict |
|---|---|---|
| 398 KB | `app/src/main/ic_launcher-playstore.png` | KEEP — Play Store icon |
| 394 KB | `app/src/main/assets/jarvis.png` | KEEP — runtime asset |
| 237 KB | `JarvisRuntime.kt` | KEEP — orchestrator (split candidate later) |
| 90 KB | `mipmap-xxxhdpi/ic_launcher_foreground.webp` | KEEP — launcher icon |
| 54 KB | `mipmap-xxhdpi/ic_launcher_foreground.webp` | KEEP — launcher icon |
| 47 KB | `SettingsStore.kt` | KEEP — central settings (acceptable for a large surface) |
| 38 KB | `FollowUpCoordinator.kt` | KEEP — single-responsibility despite size |

All "large" Kotlin files are single-responsibility orchestrators or
domain models, not duplicate code. JarvisRuntime is a refactor
candidate but out of scope for a cleanup-only sprint.

## 4. Top-level disposable artefacts

These are *tracked* and need either removal or `.gitignore` coverage:

| Path | Reason |
|---|---|
| `JARVIS_AUDIT.md` (21 KB) | Stale audit doc from May 8 — superseded by this sprint |
| `logcat.txt` (86 KB) | Captured logcat output |
| `issues_data.txt` (52 KB) | One-off GitHub-issue dump |
| `local.properties` | Machine-local SDK path — should never be tracked |
| `.idea/caches/deviceStreaming.xml` | Machine-local IDE state |
| `.idea/deploymentTargetSelector.xml` | Machine-local IDE state |
| `.idea/jarvis.iml` | Generated IDE file |
| `.idea/misc.xml` | Generated IDE file |

## 5. Build health

`./gradlew :app:compileDebugKotlin` and `:app:assembleDebug` both
**SUCCESSFUL** at baseline (verified in the immediately preceding
sprint). Unit tests pass.

## 6. Suggested cleanup actions (executed in this sprint)

1. ✅ Expand `.gitignore` to cover IDE / log / secret patterns.
2. ✅ Untrack machine-local IDE state + `local.properties`.
3. ✅ Delete top-level scratch files (`logcat.txt`, `issues_data.txt`).
4. ✅ Move `JARVIS_AUDIT.md` → `docs/audit/legacy-jarvis-audit.md`.
5. ✅ Write `file-inventory.md` + `code-duplication-audit.md`.
6. ✅ Verify build still passes after cleanup.

## 7. Items deferred (out of scope, see follow-up tickets)

- Splitting `JarvisRuntime.kt` (237 KB) into orchestrator + handlers.
- Move-package refactor toward the proposed
  `audio / speech / tts / routing / commands / phone / ...` tree
  (large blast radius — separate PR).
- Lint-driven unused-resource shrink (requires release build).
- Dependency removal audit (separate PR with full test matrix).

These are tracked in `docs/audit/file-classification.md` under
`REVIEW`.
