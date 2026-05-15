# Meta Wearables DAT — Integration Plan

The Meta Wearables DAT Android SDK ([repo](https://github.com/facebook/meta-wearables-dat-android),
[docs](https://wearables.developer.meta.com/docs/develop/dat/)) is an
optional eyes-and-hands-free module for Jarvis.  This document
records the integration plan + what's landed today.

## What's landed (this PR)

Full abstraction + stub + mock + UI + permissions + tests, with the
real SDK dependency line **deliberately deferred** so the app builds
without it.  The contract is set in stone; only the
`RealMetaWearablesProvider` slot inside
`MetaWearablesManager.pickProvider` needs filling once the SDK is on
the classpath.

| File | Role |
|---|---|
| `wearables/meta/MetaWearablesState.kt` | State machine values + transition predicates |
| `wearables/meta/WearableProviders.kt` | `WearableDeviceProvider` / `WearableCameraProvider` / `WearableContextProvider` interfaces |
| `wearables/meta/RecentVisualContext.kt` | Value-type for the last thing the glasses saw |
| `wearables/meta/StubMetaWearablesProvider.kt` | Fail-safe — always SDK_UNAVAILABLE, never throws |
| `wearables/meta/MockMetaWearablesProvider.kt` | In-memory fake — connects, captures, simulates failures |
| `wearables/meta/MetaWearablesManager.kt` | Coordinator + backend selector (stub / mock / real) |
| `wearables/meta/WearablesSettings.kt` | User-policy data class |
| `wearables/meta/WearablesSettingsRepository.kt` | StateFlow + SettingsStore wrapper |
| `ui/settings/screens/WearablesSettingsScreen.kt` | Compose screen + diagnostics |
| `tools/device/wearables/LookAtThisWearableTool.kt` | First voice tool — "look at this" / "take a glasses photo" |

## What's deferred (PR4.x — one focused change)

Once the Meta DAT SDK Maven coordinates are confirmed:

1. **Add dependencies** to `gradle/libs.versions.toml`:
   ```toml
   meta-wearables-core   = { group = "com.facebook.mwdat", name = "mwdat-core",   version = "<X.Y.Z>" }
   meta-wearables-camera = { group = "com.facebook.mwdat", name = "mwdat-camera", version = "<X.Y.Z>" }
   meta-wearables-mock   = { group = "com.facebook.mwdat", name = "mwdat-mockdevice", version = "<X.Y.Z>" }
   ```
   Mirror in `app/build.gradle.kts`:
   ```kotlin
   implementation(libs.meta.wearables.core)
   implementation(libs.meta.wearables.camera)
   debugImplementation(libs.meta.wearables.mock)   // dev only
   ```

2. **Create `wearables/meta/RealMetaWearablesProvider.kt`** implementing
   `WearableDeviceProvider + WearableCameraProvider + WearableContextProvider`
   against the SDK's session APIs.  The contract is already set; this
   is a translation layer.

3. **Wire** it into `MetaWearablesManager.pickProvider` where the
   `// TODO(meta-dat)` comment lives:
   ```kotlin
   if (sdkPresent()) {
       return RealMetaWearablesProvider(context)
   }
   ```

4. **Test on hardware** — pair, capture, stream, simulate disconnect.
   Existing `MockMetaWearablesProvider` tests stay the regression
   suite for the contract.

That's it.  No other file in the codebase needs to change — the rest
of Jarvis already consumes through the interfaces.

## Acceptance preserved

The user-facing acceptance criteria all hold today via the mock:

- "Take a glasses photo" → `LookAtThisWearableTool` captures via mock,
  URI lands in `MediaContextStore` → "show that" / "send that to Mike"
  work through the existing follow-up resolver.
- Settings show **DISCONNECTED** (mock on) or **SDK_UNAVAILABLE**
  (mock off, real SDK not on classpath) — no crashes.
- Glasses commands never route to OpenClaw / Hermes (the
  `ArchitectureInvariantsTest` R1–R3 rules cover this).

## Permissions

Added to `AndroidManifest.xml`:
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT` (already present for SCO)
- `BLUETOOTH_ADVERTISE`
- `NEARBY_WIFI_DEVICES` (with `neverForLocation`)

`CAMERA`, `RECORD_AUDIO`, `READ_MEDIA_IMAGES` are already declared for
the existing camera / recording / view-media tools.
