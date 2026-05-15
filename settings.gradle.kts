import java.util.Properties
import java.io.FileInputStream

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

// ── Meta Wearables DAT credential plumbing ───────────────────────────────
// The DAT SDK is hosted on GitHub Packages
// (https://maven.pkg.github.com/facebook/meta-wearables-dat-android), which
// requires authenticated reads.  We pull a PAT from (in order):
//   1. The GITHUB_TOKEN environment variable, or
//   2. The `github_token` key in local.properties (gitignored).
//
// Without a token Gradle simply cannot resolve `com.meta.wearable:mwdat-*`,
// the StubMetaWearablesProvider stays the active backend, and the app still
// builds — the failure surfaces as a clear "Could not resolve" error during
// Gradle sync rather than a silent runtime miss.
val localProps: Properties = Properties().apply {
    val f = file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

val githubToken: String? =
    System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty("github_token")?.takeIf { it.isNotBlank() }

// GitHub Packages **requires** the GitHub username of the token owner
// as the credential username (HTTP Basic auth).  Using a placeholder
// like "token" gets a 401.  Default to the repo owner — overridable
// per-developer via `github_username=<their-handle>` in
// local.properties or a $GITHUB_USERNAME env var.
val githubUsername: String =
    System.getenv("GITHUB_USERNAME")?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty("github_username")?.takeIf { it.isNotBlank() }
        ?: "chrisgwynne"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Meta DAT SDK — only registered when a token is available; that
        // way developers who haven't opted into the wearables module don't
        // get a confusing "401 from maven.pkg.github.com" on every build.
        if (githubToken != null) {
            maven {
                name = "MetaWearablesDatGitHubPackages"
                url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                credentials {
                    username = githubUsername
                    password = githubToken
                }
            }
        }
    }
}

rootProject.name = "Jarvis"
include(":app")
