# File Classification

## KEEP (no action)

- All `app/src/main/java/**/*.kt` (637 files) — actively used.
- All `app/src/test/java/**/*.kt` — unit-test coverage.
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/**` — launcher icons, drawables, XML configs all
  referenced by manifest / theme / FileProvider.
- `app/src/main/assets/jarvis.png` — referenced at runtime.
- `gradle/`, `gradlew`, `gradlew.bat`, `gradle.properties`,
  `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
  `app/proguard-rules.pro` — Gradle build inputs.
- `CLAUDE.md` — project conventions.
- `docs/**` — kept-and-curated documentation.
- `.github/workflows/android-ci.yml` — CI definition.
- `.idea/.gitignore`, `.idea/codeStyles/**`, `.idea/jarvis.iml`,
  `.idea/AndroidProjectSystem.xml`, `.idea/gradle.xml`,
  `.idea/compiler.xml`, `.idea/migrations.xml`,
  `.idea/runConfigurations.xml`, `.idea/vcs.xml` — *shared* IDE
  config that's intentionally tracked.

## DELETE_SAFE (removed in this sprint)

| Path | Reason |
|---|---|
| `logcat.txt` | One-off log capture |
| `issues_data.txt` | One-off GitHub-issues dump |
| `JARVIS_AUDIT.md` → `docs/audit/legacy-jarvis-audit.md` | Renamed, not deleted, to preserve history but get out of the top level |

## UNTRACK (rm --cached, then `.gitignore`)

| Path | Reason |
|---|---|
| `local.properties` | Contains `sdk.dir=...` — strictly machine-local. The Android Gradle plugin regenerates / requires it locally; tracking it forces every contributor to merge over each other's SDK path. |
| `.idea/caches/deviceStreaming.xml` | IDE device-streaming cache |
| `.idea/deploymentTargetSelector.xml` | Per-machine deploy target |
| `.idea/misc.xml` | Generated; flips on every IDE launch |
| `.idea/assetWizardSettings.xml` | Per-user asset wizard prefs |
| `.idea/.name` | Per-user IDE display name |

## REVIEW (deferred — separate PR)

These need careful follow-up, not a blind delete:

| Item | Why it needs a separate PR |
|---|---|
| Split `JarvisRuntime.kt` (237 KB) | Touches every subsystem; needs careful API extraction. |
| Move-package refactor to `audio/`, `speech/`, `tts/`, `routing/`, … | Massive blast radius — every import touched. Run as a single mechanical rename PR. |
| Lint-driven unused-resource shrink | Requires `./gradlew lintRelease` + manual review (false positives on dynamically-resolved IDs). |
| Dependency removal audit | Needs the full test suite to verify nothing breaks. |
| Mipmap variants for low-density buckets | `mipmap-hdpi` / `mipmap-mdpi` may be redundant on a phone-first product; needs design call. |

## Singleton invariant audit

Each of the following classes MUST exist exactly once in
`app/src/main` — verified at baseline:

- `TranscriptNormalizer`
- `RecentActionContextStore`
- `UserSafeErrorHandler`
- `SessionContinuationPolicy`
- `CommandPermissionPolicy`
- `ContextualFollowupParser`
- `ProactivityGate`
- `ProactivitySettings`

Recommend wiring a `:app:checkSingletons` gradle task that fails the
build if any of these grows a second definition. Tracked as a
follow-up.
