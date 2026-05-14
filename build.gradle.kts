// Top-level build file — plugin version declarations only.
// Nothing is applied here; each module applies what it needs.
plugins {
    // AGP 9+ has built-in Kotlin support; the standalone kotlin.android
    // plugin is intentionally absent (see app/build.gradle.kts).
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
