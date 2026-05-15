# Meta Wearables DAT — Integration Plan

The Meta Wearables DAT Android SDK ([repo](https://github.com/facebook/meta-wearables-dat-android),
[docs](https://wearables.developer.meta.com/docs/develop/dat/)) is an
optional eyes-and-hands-free module for Jarvis.

## Current status

| Phase | Status |
|---|---|
| Abstraction layer (state machine, providers, manager) | ✅ Shipped |
| Stub + mock backends | ✅ Shipped |
| Settings UI + manifest perms + voice tool | ✅ Shipped |
| Manifest `<meta-data>` for App ID + Client Token (build-time injection) | ✅ Shipped |
| Maven repo + dependency wiring (GitHub Packages, token-gated) | ✅ Shipped |
| `RealMetaWearablesProvider` — actual SDK binding | ⏳ Awaiting class-name confirmation |
| Hardware smoke test | ⏳ After provider lands |

## What you need on your machine

Three keys in `local.properties` (gitignored — never committed):

```properties
sdk.dir=...

# Meta DAT credentials (from Meta Developer Console)
meta.wearables.applicationId=1325562139458804
meta.wearables.clientToken=AR|1325562139458804|<your_token>

# GitHub PAT with `read:packages` scope — required to fetch the
# DAT SDK from https://maven.pkg.github.com/facebook/meta-wearables-dat-android
# Generate at: https://github.com/settings/tokens (Fine-grained or classic;
# classic needs `read:packages`, fine-grained needs read access to the
# `facebook/meta-wearables-dat-android` repo).
github_token=ghp_XXXXXXXXXXXXXXXXXXXXXXXXXXXX
```

Without `github_token`, Gradle never sees the DAT Maven repo, the
`mwdat-*` deps are simply not declared, and Jarvis builds + runs
exactly as before — the `StubMetaWearablesProvider` remains the
active backend and Settings → Wearables shows `SDK_UNAVAILABLE`.

## How the wiring works

1. **`settings.gradle.kts`** reads `github_token` from `local.properties`
   (or `$GITHUB_TOKEN`).  When present, registers
   `https://maven.pkg.github.com/facebook/meta-wearables-dat-android`
   as an authenticated Maven repo.  When absent, the repo is **not
   registered at all** — so a fresh clone doesn't get a confusing
   401 on every build.

2. **`gradle/libs.versions.toml`** declares three artifacts under
   `com.meta.wearable`, version `0.7.0`:
   - `mwdat-core`
   - `mwdat-camera`
   - `mwdat-mockdevice` (debug-only)

3. **`app/build.gradle.kts`** declares `implementation(libs.mwdat.core)`
   etc. **only when the token is present**, mirroring the
   `settings.gradle.kts` gating.  Both checks read the same key the
   same way.

4. **`AndroidManifest.xml`** injects the App ID + Client Token
   via `manifestPlaceholders` — values live in `local.properties`,
   only placeholder names ship in git.

## What's still pending (the actual provider)

The DAT SDK's public class names + method signatures aren't visible
without logging into the Meta developer docs site
(https://wearables.developer.meta.com/docs/reference/android/dat/0.7).
The repo's README + sample READMEs don't include them and the
sample sources aren't world-readable through unauthenticated GitHub
file fetches.

Once the dependency resolves on the developer's machine (i.e. after
`github_token` is set and `./gradlew build` succeeds), there are
two paths to fill in `RealMetaWearablesProvider`:

**A — IDE inspection.**  In Android Studio, expand
`External Libraries → mwdat-core-0.7.0 → com.meta.wearable.mwdat.core`
and paste the class list back; I write the provider from that.

**B — Sample app.**  Run the `samples/CameraAccess` app from the
official repo and copy its `MainActivity.kt` (or `MainScreen.kt`) —
that's the canonical Hello World.  Paste it back; I translate.

**C — API reference.**  Log into the gated docs page, paste the
class + method list for `mwdat-core` and `mwdat-camera`.

Any one of those unblocks the final step.

## Final wiring (after provider lands)

Replace the `// TODO(meta-dat)` slot in `MetaWearablesManager.pickProvider`:

```kotlin
if (sdkPresent()) {
    return RealMetaWearablesProvider(context)
}
```

`sdkPresent()` already does a classpath probe — flip its
`Class.forName(...)` argument to the actual SDK class name once we
know it.

## How to test

With token configured + SDK on classpath but provider not yet
implemented:
- Settings → Wearables shows `SDK_UNAVAILABLE` still (because the
  `sdkPresent()` probe looks for `com.facebook.mwdat.core.MwDatClient`
  which doesn't exist — the real Meta package is `com.meta.wearable`).
  This is correct fail-safe behaviour; flipping the probe is the
  one-line follow-up.

With mock toggle ON:
- Full state machine + capture flow works without any of the above.
  Already verified end-to-end.

## Acceptance preserved today

Even with the SDK on the classpath but no provider yet:
- App builds.
- All existing voice commands work.
- Glasses commands return `SDK_UNAVAILABLE` and fall through to
  phone-camera / screenshot.
- No crashes, no raw SDK errors spoken.

The `ArchitectureInvariantsTest` R1–R3 rules (glasses commands
never route to OpenClaw / Hermes) still pass.
