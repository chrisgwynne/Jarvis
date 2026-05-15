import java.util.Properties
import java.io.FileInputStream

plugins {
    // AGP 9+ ships built-in Kotlin support — the standalone
    // `org.jetbrains.kotlin.android` plugin must NOT be applied alongside
    // it (KGP 2.2.20 fails fast with a clear error if you do).
    // See https://issuetracker.google.com/438678642
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // ── Meta Wearables DAT — Application ID + Client Token ────────────
        // Loaded from local.properties (gitignored) so the token never
        // lands in the public repo.  The keys are:
        //   meta.wearables.applicationId=<numeric id from Meta dev console>
        //   meta.wearables.clientToken=AR|<id>|<token>
        // If unset, both fall back to an empty string and the manifest
        // entries are still present but inert — Meta's SDK falls back to
        // Developer Mode (per their docs: "Do not set it if you are using
        // Developer Mode to test your integration").
        val metaProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) FileInputStream(f).use { load(it) }
        }
        manifestPlaceholders["metaWearablesApplicationId"] =
            metaProps.getProperty("meta.wearables.applicationId", "")
        manifestPlaceholders["metaWearablesClientToken"] =
            metaProps.getProperty("meta.wearables.clientToken", "")
    }

    // Tier-A: let JVM unit tests return default values from un-mocked
    // android.* APIs (e.g. android.util.Log) instead of throwing
    // RuntimeException("not mocked").  Without this any pure-JVM test that
    // touches Log.d via production code crashes.  This is the modern Android
    // best practice and reduces the pre-existing failure count.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }


    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// AGP 9's built-in Kotlin support exposes the Kotlin DSL at the top level
// (the old `android { kotlinOptions { … } }` form went away with the
// standalone `org.jetbrains.kotlin.android` plugin).  Pin the JVM target
// to 17 so it matches `compileOptions` above.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM — all compose versions come from here
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Encrypted storage
    implementation(libs.security.crypto)

    // Biometric + device-credential prompt for the app-level lock (Phase 4b)
    implementation(libs.androidx.biometric)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Networking (for LLM API calls).  OkHttp + Gson only — NetworkClient builds
    // requests against OkHttp directly, so Retrofit and the logging-interceptor
    // are intentionally absent (kept the APK ~1.2 MB smaller and avoids the
    // interceptor accidentally leaking bodies into logcat).
    implementation(libs.okhttp)
    implementation(libs.gson)

    // LeakCanary — debug-only memory leak detector. Not packaged into release.
    debugImplementation(libs.leakcanary.android)

    // Runtime permission handling in Compose
    implementation(libs.accompanist.permissions)

    // LocalBroadcastManager (removed from core-ktx, now a standalone artifact)
    implementation(libs.localbroadcastmanager)

    // Google Play Services — Geofencing for location-aware reminders
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Room — persistent memory + conversation history
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // TensorFlow Lite — neural speaker embedding engine
    // Place speaker_encoder.tflite in app/src/main/assets/ to activate.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // Baseline Profile installer — installs compiled profile on first launch
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // CameraX — headless still-image capture from foreground service (no Activity needed)
    // camera-camera2 provides the Camera2 implementation; camera-lifecycle provides
    // ProcessCameraProvider + bindToLifecycle (used with ServiceLifecycleOwner).
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")

    // Firebase — optional cloud sync. Initialised at RUNTIME via FirebaseOptions
    // read from SettingsStore, so the google-services plugin is NOT applied and
    // google-services.json is NOT required at build time. Users who never sign
    // in pay for the dependency footprint only (~1.5 MB).
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    // Bridges Firebase Task<T> → coroutine .await()
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    // org.json is provided by the Android platform at runtime but absent on
    // the JVM unit-test classpath. Pull it in so tests that round-trip JSON
    // (e.g. ScreenObservationRepositoryTest) don't hit "Method not mocked".
    testImplementation("org.json:json:20240303")

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)

    // ── Meta Wearables DAT (optional, gated on github_token) ──────────────
    // The dependency resolves only when settings.gradle.kts registered the
    // GitHub Packages repo (i.e. a token was found in local.properties or
    // the GITHUB_TOKEN env var).  Without a token the deps simply aren't
    // declared — the StubMetaWearablesProvider stays the active backend
    // and Jarvis still builds.  See docs/wearables/meta-dat-integration.md.
    val mwdatTokenPresent = (System.getenv("GITHUB_TOKEN")?.isNotBlank() == true) ||
        run {
            val f = rootProject.file("local.properties")
            f.exists() && Properties().apply { FileInputStream(f).use { load(it) } }
                .getProperty("github_token")?.isNotBlank() == true
        }
    if (mwdatTokenPresent) {
        implementation(libs.mwdat.core)
        implementation(libs.mwdat.camera)
        debugImplementation(libs.mwdat.mockdevice)
    }
}

// ── Meta Wearables DAT — conditional source set ─────────────────────────
// `app/src/mwdat/java/` holds RealMetaWearablesProvider which directly
// imports `com.meta.wearable.mwdat.*` types.  Those types only resolve
// when the SDK is on the classpath, which requires `github_token` in
// local.properties.  We include the directory only in that case so a
// fresh clone without a token still compiles cleanly — the stub stays
// active, the rest of the app is unchanged.
val mwdatSrcEnabled: Boolean = (System.getenv("GITHUB_TOKEN")?.isNotBlank() == true) ||
    run {
        val f = rootProject.file("local.properties")
        f.exists() && Properties().apply { FileInputStream(f).use { load(it) } }
            .getProperty("github_token")?.isNotBlank() == true
    }
if (mwdatSrcEnabled) {
    android.sourceSets.getByName("main").java.srcDir("src/mwdat/java")
    logger.lifecycle("[META_WEARABLES] Including src/mwdat/java source set " +
        "(github_token present → DAT SDK available).")
}

/**
 * Architecture invariants — runs the pure-JVM source scanner under
 * `ArchitectureInvariantsTest`.  See
 * `docs/architecture/routing-invariants.md` for the rules and
 * `app/src/test/java/com/jarvis/assistant/architecture/` for the
 * enforcement code.
 *
 * Wired to `check` so a normal CI run picks it up too.  Run on its
 * own with:
 *   ./gradlew :app:checkArchitectureInvariants
 */
tasks.register("checkArchitectureInvariants") {
    group       = "verification"
    description = "Enforce routing + extraction invariants (see docs/architecture/routing-invariants.md)."
    dependsOn(tasks.named("testDebugUnitTest"))
    doLast {
        logger.lifecycle("✓ Architecture invariants verified — see ArchitectureInvariantsTest.")
    }
}

tasks.named("check") { dependsOn("checkArchitectureInvariants") }

/**
 * AGP 9 dropped the legacy `unitTestClasses` task that older
 * IntelliJ / Android Studio test runners (and some external test
 * tools) still invoke when you tap the green "Run tests" arrow.
 * Without this alias the IDE fails with
 *   "Cannot locate tasks that match ':app:unitTestClasses'"
 *
 * Register it as an alias that depends on the real compile +
 * resource tasks for the debug unit-test source set.  Running this
 * task produces the same classpath the JVM test runner needs, so
 * the IDE play button works again without us migrating every dev
 * machine's run-configuration XML.
 */
tasks.register("unitTestClasses") {
    group       = "build"
    description = "AGP-9 compatibility alias for legacy IDE test runners."
    dependsOn(
        tasks.named("compileDebugUnitTestKotlin"),
        tasks.named("processDebugUnitTestJavaRes"),
        tasks.named("processDebugJavaRes"),
    )
}
