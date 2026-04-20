plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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

    kotlinOptions {
        jvmTarget = "17"
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

    // Networking (for LLM API calls).  OkHttp + Gson only — Retrofit and the
    // OkHttp logging interceptor were declared historically but never wired in
    // (NetworkClient builds requests against OkHttp directly).  Dropping them
    // shrinks the release APK by ~1.2 MB and avoids logging-interceptor
    // accidentally leaking bodies into logcat.
    implementation(libs.okhttp)
    implementation(libs.gson)

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

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
