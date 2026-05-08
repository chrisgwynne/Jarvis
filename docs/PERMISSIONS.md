# Permissions

Every Android permission Jarvis declares maps to a concrete in-app feature.
This page is the source of truth so reviewers (and the Play Store) can
verify the manifest isn't asking for more than it uses.

If you add a permission to `AndroidManifest.xml`, add a row here and
identify the feature that needs it.  Unused permissions are removed.

## Runtime / dangerous permissions

| Permission                            | Used by                                                      | Notes |
|---------------------------------------|--------------------------------------------------------------|-------|
| `RECORD_AUDIO`                        | Wake-word detector, `SpeechCapture`, `BargeInDetector`, `AudioRecordingTool` | Requested at first launch |
| `CAMERA`                              | `CameraCaptureManager`, `AnalyzeCameraViewTool`              | Requested only when first vision tool runs |
| `CALL_PHONE`                          | `OutgoingCallController`, `CallTool`                         | |
| `ANSWER_PHONE_CALLS`                  | `TelecomCallActionExecutor`                                  | API 26+ runtime grant |
| `READ_PHONE_STATE`                    | `TelephonyCallMonitor` (incoming-call detection)             | |
| `READ_CONTACTS`                       | `ContactsPhoneLookupResolver`, `ContactLookup`               | |
| `SEND_SMS`                            | `SmsTool`                                                    | |
| `READ_CALENDAR`, `WRITE_CALENDAR`     | `CalendarTool`                                               | |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | `CurrentLocationProvider`                          | Used to inject city + driving-mode hints |
| `ACCESS_BACKGROUND_LOCATION`          | `LocationReminderManager` (geofences)                        | Two-step grant on Android 10+ |
| `BLUETOOTH_CONNECT` (API 31+)         | `BluetoothScoManager` (headset routing)                      | |
| `POST_NOTIFICATIONS` (API 33+)        | Foreground-service notification, reminder alarms             | |

## Special-access permissions (user-grants in system Settings)

| Permission                            | Used by                                                      | Grant flow |
|---------------------------------------|--------------------------------------------------------------|------------|
| `PACKAGE_USAGE_STATS`                 | `ForegroundAppEventAdapter`                                  | Settings → Apps → Special access → Usage access |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`| `ServiceHealthBanner` link, first-run dialog                 | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| `SCHEDULE_EXACT_ALARM` (API 31+)      | `ReminderScheduler`                                          | `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` |
| `BIND_NOTIFICATION_LISTENER_SERVICE`  | `JarvisNotificationListener`                                 | Settings → Notification access |
| `BIND_CALL_SCREENING_SERVICE`         | `JarvisCallScreeningService`                                 | Phone app → Default screening app |
| `BIND_ACCESSIBILITY_SERVICE`          | `JarvisAccessibilityService` (opt-in, screen context)        | Settings → Accessibility |

## Normal-protection permissions (auto-granted)

| Permission                | Used by                                            |
|---------------------------|----------------------------------------------------|
| `INTERNET`                | LLM providers, HomeAssistant, web search           |
| `ACCESS_NETWORK_STATE`    | `NetworkEventAdapter`, `OfflineManager`            |
| `ACCESS_WIFI_STATE`       | `NetworkEventAdapter` (SSID inspection)            |
| `MODIFY_AUDIO_SETTINGS`   | `AudioFocusManager`, `BluetoothScoManager`         |
| `FOREGROUND_SERVICE`      | `JarvisService`                                    |
| `FOREGROUND_SERVICE_MICROPHONE` (API 34+) | Wake-word + STT capture inside the FGS  |
| `FOREGROUND_SERVICE_CAMERA`     (API 34+) | Headless camera capture inside the FGS  |
| `RECEIVE_BOOT_COMPLETED`  | `BootReceiver` (auto-start on boot, opt-out in settings) |
| `CHANGE_CONFIGURATION`    | `DrivingModeManager` (`UiModeManager.enableCarMode`) |
| `VIBRATE`                 | Wake-word haptic tick                              |

## Capped-by-version permissions

| Permission                        | Cap                       | Reason |
|-----------------------------------|---------------------------|--------|
| `WRITE_EXTERNAL_STORAGE`          | `maxSdkVersion="28"`      | MediaStore inserts work without it on Android 10+ |
| `BLUETOOTH` (legacy)              | `maxSdkVersion="30"`      | API 31+ moved the runtime grant to `BLUETOOTH_CONNECT` |

## Removed (and why)

| Permission                         | Removed in commit            | Reason |
|------------------------------------|------------------------------|--------|
| `USE_EXACT_ALARM`                  | Phase 1 round 1 (`417a052`)  | Auto-granted only for the clock/calendar/alarm-clock app category — risks Play Store rejection without functional benefit.  `SCHEDULE_EXACT_ALARM` covers the same code path with the user-grant flow. |

## Permissions deliberately not declared (despite spec)

The original Phase 5 spec lists ~30 additional permissions
(`READ_SMS`, `WRITE_CONTACTS`, `READ_CALL_LOG`, `READ_MEDIA_*`,
`BLUETOOTH_SCAN`, `ACTIVITY_RECOGNITION`, `BODY_SENSORS`,
`SYSTEM_ALERT_WINDOW`, `NFC`, `NEARBY_WIFI_DEVICES`, …) that we *could*
declare for "capability-readiness."  We don't, by policy:

* Play Store flags unused dangerous permissions and rejects apps that
  ask for more than they need.
* Each permission needs a concrete in-app feature with a reasonable
  rationale screen.

When a feature lands that needs one of those permissions, add it to the
manifest **and** add a row to this table in the same commit.
