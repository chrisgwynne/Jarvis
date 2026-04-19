# ============================================================
# Jarvis Assistant — ProGuard / R8 rules
# ============================================================
# Base rules (pre-existing)
# ============================================================

# Keep Jarvis service and receiver
-keep class com.jarvis.assistant.service.** { *; }
-keep class com.jarvis.assistant.llm.** { *; }

# OkHttp — Retrofit is no longer a dependency; keep-rule removed.
-dontwarn okhttp3.**

# ============================================================
# 1. Room — keep @Entity, @Dao, @Database annotated classes
# ============================================================
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers @androidx.room.Entity class * { *; }
-keepclassmembers @androidx.room.Dao interface * { *; }

# ============================================================
# 2. Gson — keep all Gson-serialized data classes
# ============================================================
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class * implements com.google.gson.TypeAdapterFactory { *; }
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }

# ============================================================
# 3. OkHttp + Retrofit (extended)
# ============================================================
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ============================================================
# 4. Kotlin sealed classes / enums (security package, PolicyResult, etc.)
# ============================================================
-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================
# 5. AndroidX Security — EncryptedSharedPreferences
# ============================================================
-keep class androidx.security.crypto.** { *; }

# ============================================================
# 6. Kotlin Coroutines
# ============================================================
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }

# ============================================================
# 7. Jarvis-specific — keep serialized / reflected data classes
# ============================================================
-keep class com.jarvis.assistant.security.** { *; }
-keep class com.jarvis.assistant.followup.ActiveFlow { *; }
-keep class com.jarvis.assistant.followup.FlowType { *; }
-keep class com.jarvis.assistant.followup.SlotKey { *; }
-keep class com.jarvis.assistant.reminders.ScheduledItem { *; }
-keep class com.jarvis.assistant.shopping.ShoppingItem { *; }

# ============================================================
# 7b. Reporting — GitHub issue payload + client response are serialised
#     via Gson by field name (no @SerializedName annotations), so R8
#     must not rename those fields or the JSON wire format breaks.
# ============================================================
-keep class com.jarvis.assistant.reporting.github.GitHubIssuePayload { *; }
-keep class com.jarvis.assistant.reporting.github.PendingReportStore$Persisted { *; }
-keep class com.jarvis.assistant.reporting.github.GitHubIssueClient$CreateResponse { *; }

# ============================================================
# 8. CameraX — keep lifecycle and capture classes
# ============================================================
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ============================================================
# 9. Jarvis camera + recording packages
#    VisionClient and RecordingTranscriber use org.json (no Gson reflection),
#    but keep the tool/manager classes themselves from name-mangling.
# ============================================================
-keep class com.jarvis.assistant.camera.** { *; }
-keep class com.jarvis.assistant.audio.recording.** { *; }

# ============================================================
# 11. OpenClaw — keep remote routing classes from name-mangling
# ============================================================
-keep class com.jarvis.assistant.remote.openclaw.** { *; }

# ============================================================
# 10. Remove verbose debug/trace logging in release builds
#    (Log.i / Log.w / Log.e are kept for production visibility)
# ============================================================
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
