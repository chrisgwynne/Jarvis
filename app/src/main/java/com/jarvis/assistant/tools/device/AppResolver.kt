package com.jarvis.assistant.tools.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * AppResolver — maps a spoken app name to a launchable package.
 *
 * Resolution order (first match wins, per Jarvis spec APP ACTION SYSTEM):
 *   1. Learned alias map   — previously confirmed spoken-form ↔ package
 *   2. Built-in alias map  — common rebrands / phonetic variants / shortforms
 *   3. Installed app label — fuzzy substring match against loadLabel()
 *   4. Package-name match  — direct substring match on packageName
 *   5. Launcher intent     — category-based intents (camera, maps, messaging)
 *
 * Only fails after all five tiers return nothing.  On success the raw spoken
 * alias is persisted via [aliasStore] so subsequent calls hit tier 1 directly.
 */
class AppResolver(
    private val context: Context,
    private val aliasStore: AppAliasStore
) {

    companion object {
        private const val TAG = "AppResolver"

        /**
         * Built-in aliases for common rebrands, phonetic misreads, and
         * colloquial names.  Package names are the current Play Store IDs at
         * time of writing.  Missing entries fall through to fuzzy matching,
         * so this list is not load-bearing.
         */
        private val BUILT_IN_ALIASES: Map<String, String> = mapOf(
            // Music & media
            "spotify"             to "com.spotify.music",
            "spotify music"       to "com.spotify.music",
            "youtube"             to "com.google.android.youtube",
            "youtube music"       to "com.google.android.apps.youtube.music",
            "yt music"            to "com.google.android.apps.youtube.music",
            "netflix"             to "com.netflix.mediaclient",
            "amazon music"        to "com.amazon.mp3",
            "apple music"         to "com.apple.android.music",
            "shazam"              to "com.shazam.android",
            "twitch"              to "tv.twitch.android.app",
            "soundcloud"          to "com.soundcloud.android",
            // Social & messaging
            "whatsapp"            to "com.whatsapp",
            "whatsapp business"   to "com.whatsapp.w4b",
            "instagram"           to "com.instagram.android",
            "facebook"            to "com.facebook.katana",
            "messenger"           to "com.facebook.orca",
            "telegram"            to "org.telegram.messenger",
            "signal"              to "org.thoughtcrime.securesms",
            "twitter"             to "com.twitter.android",
            "x"                   to "com.twitter.android",
            "tiktok"              to "com.zhiliaoapp.musically",
            "snapchat"            to "com.snapchat.android",
            "discord"             to "com.discord",
            "reddit"              to "com.reddit.frontpage",
            "linkedin"            to "com.linkedin.android",
            "pinterest"           to "com.pinterest",
            "slack"               to "com.Slack",
            "teams"               to "com.microsoft.teams",
            "microsoft teams"     to "com.microsoft.teams",
            "zoom"                to "us.zoom.videomeetings",
            // Payments
            "paypal"              to "com.paypal.android.p2pmobile",
            "venmo"               to "com.venmo",
            // Shopping
            "amazon"              to "com.amazon.mShop.android.shopping",
            "amazon shopping"     to "com.amazon.mShop.android.shopping",
            "ebay"                to "com.ebay.mobile",
            // Navigation
            "google maps"         to "com.google.android.apps.maps",
            "maps"                to "com.google.android.apps.maps",
            "waze"                to "com.waze",
            // Transport
            "uber"                to "com.ubercab",
            "uber eats"           to "com.ubercab.eats",
            "lyft"                to "me.lyft.android",
            // Google apps
            "gmail"               to "com.google.android.gm",
            "chrome"              to "com.android.chrome",
            "google drive"        to "com.google.android.apps.docs",
            "drive"               to "com.google.android.apps.docs",
            "google docs"         to "com.google.android.apps.docs.editors.docs",
            "docs"                to "com.google.android.apps.docs.editors.docs",
            "google sheets"       to "com.google.android.apps.docs.editors.sheets",
            "sheets"              to "com.google.android.apps.docs.editors.sheets",
            "google photos"       to "com.google.android.apps.photos",
            "photos"              to "com.google.android.apps.photos",
            "google meet"         to "com.google.android.apps.meetings",
            "meet"                to "com.google.android.apps.meetings",
            "camera"              to "com.google.android.GoogleCamera",
            "google camera"       to "com.google.android.GoogleCamera",
            "clock"               to "com.google.android.deskclock",
            "calendar"            to "com.google.android.calendar",
            "google calendar"     to "com.google.android.calendar",
            "calculator"          to "com.google.android.calculator",
            "files"               to "com.google.android.apps.nbu.files",
            "google files"        to "com.google.android.apps.nbu.files",
            "play store"          to "com.android.vending",
            "google play"         to "com.android.vending",
            // Microsoft
            "outlook"             to "com.microsoft.office.outlook",
            "word"                to "com.microsoft.office.word",
            "excel"               to "com.microsoft.office.excel",
            "powerpoint"          to "com.microsoft.office.powerpoint",
            "onenote"             to "com.microsoft.office.onenote",
            // System
            "settings"            to "com.android.settings"
        )

        /**
         * Category-based intent fallbacks — used when no package can be matched
         * by name but the spoken alias matches a generic capability.
         */
        private val CATEGORY_INTENTS: Map<String, () -> Intent> = mapOf(
            "camera"    to { Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) },
            "phone"     to { Intent(Intent.ACTION_DIAL) },
            "dialer"    to { Intent(Intent.ACTION_DIAL) },
            "browser"   to { Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com")) },
            "messages"  to { Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_MESSAGING) } },
            "contacts"  to { Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_CONTACTS) } }
        )
    }

    /** Resolution outcome returned by [resolve]. */
    sealed class Result {
        /**
         * Package found; call [packageName] via [PackageManager.getLaunchIntentForPackage].
         *
         * [confidence] = HIGH when the match is direct (learned alias, built-in alias,
         * exact/prefix label, or installed package name). LOW when the match came from
         * a broad substring match — callers should ask "Did you mean X?" once before
         * launching and persisting the alias.
         */
        data class Launchable(
            val packageName: String,
            val displayLabel: String,
            val confidence: Confidence = Confidence.HIGH
        ) : Result()

        /** No package but a generic intent ([intent]) is ready to start. */
        data class GenericIntent(val intent: Intent, val displayLabel: String) : Result()

        /** Nothing matched. */
        object NotFound : Result()
    }

    enum class Confidence { HIGH, LOW }

    /** Internal label match with the confidence tier it came from. */
    private data class LabelMatch(val info: ResolveInfo, val confidence: Confidence)

    /** Attempt to resolve [spokenName] against the five-tier order. */
    fun resolve(spokenName: String): Result {
        val query = spokenName.trim()
        if (query.isEmpty()) return Result.NotFound
        // Normalize before lookup so phrasing noise doesn't break resolution:
        //   "open up Spotify"          → "Spotify"
        //   "open the YouTube app"     → "YouTube"
        //   "launch Spotify please"    → "Spotify"
        //   "play Spotify for me"      → "Spotify"
        val lower = normalizeQuery(query.lowercase())

        // 1. Learned alias
        aliasStore.get(lower)?.let { pkg ->
            if (isInstalled(pkg)) {
                Log.d(TAG, "Resolved '$query' via learned alias → $pkg")
                return Result.Launchable(pkg, labelFor(pkg) ?: query)
            } else {
                // Stale — package no longer installed; drop it
                aliasStore.remove(lower)
            }
        }

        // 2. Built-in alias map
        BUILT_IN_ALIASES[lower]?.let { pkg ->
            if (isInstalled(pkg)) {
                Log.d(TAG, "Resolved '$query' via built-in alias → $pkg")
                return Result.Launchable(pkg, labelFor(pkg) ?: query)
            }
        }

        // 3. Installed-app label fuzzy match (substring, case-insensitive)
        val labelHit = findByLabel(lower)
        if (labelHit != null) {
            val pkg = labelHit.info.activityInfo.packageName
            Log.d(TAG, "Resolved '$query' via label match (${labelHit.confidence}) → $pkg")
            return Result.Launchable(
                pkg,
                labelHit.info.loadLabel(context.packageManager).toString(),
                labelHit.confidence
            )
        }

        // 4. Direct package-name substring match — always LOW confidence
        val pkgHit = findByPackage(lower)
        if (pkgHit != null) {
            Log.d(TAG, "Resolved '$query' via package-name match → $pkgHit")
            return Result.Launchable(pkgHit, labelFor(pkgHit) ?: query, Confidence.LOW)
        }

        // 5. Category intent fallback
        CATEGORY_INTENTS[lower]?.invoke()?.let { intent ->
            if (intent.resolveActivity(context.packageManager) != null) {
                Log.d(TAG, "Resolved '$query' via category intent")
                return Result.GenericIntent(intent, query)
            }
        }

        return Result.NotFound
    }

    /**
     * Persist a confirmed alias after a successful launch.
     * Safe to call with a [Launchable] outcome — no-op for generic intents.
     *
     * Stores under the normalized key so future lookups hit tier 1 regardless
     * of how the user phrased the original command (e.g. "up Spotify" is stored
     * as "spotify" so "open up Spotify" resolves instantly next time).
     */
    fun rememberAlias(spokenName: String, result: Result, durable: Boolean = false) {
        if (result is Result.Launchable) {
            val normalized = normalizeQuery(spokenName.lowercase())
            aliasStore.put(normalized, result.packageName, durable = durable)
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Strip spoken-form noise so the core app name reaches the alias/label tiers.
     *
     * Handles common patterns that would otherwise cause resolution to fail:
     *   "up Spotify"       → "Spotify"    (leading filler from "open up X")
     *   "the YouTube app"  → "YouTube"    (article + trailing "app")
     *   "Spotify please"   → "Spotify"    (trailing courtesy words)
     *   "Spotify for me"   → "Spotify"    (trailing phrase)
     */
    private fun normalizeQuery(raw: String): String {
        var s = raw.trim().trimEnd('?', '.', '!')

        // Strip any combination of leading fillers ("open up a spotify",
        // "open the new spotify") by looping until nothing else matches.
        val leadingFillers = listOf("up ", "the ", "a ", "an ", "my ", "some ")
        var changed = true
        while (changed) {
            changed = false
            for (filler in leadingFillers) {
                if (s.startsWith(filler)) { s = s.removePrefix(filler).trim(); changed = true }
            }
        }

        // Strip trailing noise phrases; loop so stacked fillers all peel off
        // ("spotify please now" → "spotify").  Ordered longest-first to avoid
        // partial strips ("for me please" before "please").
        val trailingNoise = listOf(
            " for me please", " for me", " right now please", " right now",
            " please", " now", " quickly", " immediately"
        )
        changed = true
        while (changed) {
            changed = false
            for (noise in trailingNoise) {
                if (s.endsWith(noise)) { s = s.removeSuffix(noise).trim(); changed = true; break }
            }
        }

        // Strip trailing "app" / "application"
        s = s.removeSuffix(" application").removeSuffix(" app").trim()
        return s
    }

    /**
     * Check if [packageName] is installed.
     *
     * getLaunchIntentForPackage() is the primary check but some OEM battery
     * optimisers (MIUI, One UI) return null for suspended/optimized apps even
     * when they are installed.  getPackageInfo() is used as a fallback — if it
     * succeeds the package at least exists on disk; the launcher intent failure
     * is then surfaced as "found but can't launch" in OpenAppTool rather than
     * the misleading "I don't see X on your phone."
     */
    private fun isInstalled(packageName: String): Boolean {
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(packageName) != null) return true
        return try {
            pm.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun labelFor(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    // Cached list of launcher activities.  queryIntentActivities() is a
    // cross-process binder call and its label lookups decode per-APK resources,
    // so a single resolve() previously did N label decodes twice (once for
    // findByLabel, once for findByPackage).  Cache for 30 s — package installs
    // or uninstalls just mean one stale resolve, which gets corrected on the
    // next tick or on explicit invalidate().
    private data class CachedLaunchers(val at: Long, val labeled: List<Pair<ResolveInfo, String>>)
    @Volatile private var launchersCache: CachedLaunchers? = null
    private val launchersCacheTtlMs = 30_000L

    /** Force a reload of the cached launcher list — call on PACKAGE_ADDED / REMOVED. */
    fun invalidateLauncherCache() { launchersCache = null }

    private fun launchers(): List<Pair<ResolveInfo, String>> {
        val snapshot = launchersCache
        val now = System.currentTimeMillis()
        if (snapshot != null && now - snapshot.at < launchersCacheTtlMs) return snapshot.labeled
        val pm = context.packageManager
        val activities = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.GET_META_DATA
        )
        val labeled = activities.map { it to it.loadLabel(pm).toString().lowercase() }
        launchersCache = CachedLaunchers(now, labeled)
        return labeled
    }

    /**
     * First launcher activity whose label matches [needle] (case-insensitive).
     *
     * Match priority (first wins):
     *   1. Exact:       label == needle                ("spotify" → "Spotify")
     *   2. StartsWith:  label starts with needle       ("google" → "Google Maps")
     *   3. Needle in label: label contains needle      ("tube" → "YouTube")
     *   4. Label in needle: needle contains label      ("open spotify now" contains "spotify")
     *
     * Direction 4 is intentionally last — it is the broadest and could cause
     * false positives for very short app names.  A minimum label length of 4
     * chars guards against single-word collisions ("go", "hi", etc.).
     */
    private fun findByLabel(needle: String): LabelMatch? {
        val lowerNeedle = needle.lowercase()
        val labeled = launchers()

        // HIGH: exact or prefix — unambiguous
        labeled.firstOrNull { it.second == lowerNeedle }
            ?.let { return LabelMatch(it.first, Confidence.HIGH) }
        labeled.firstOrNull { it.second.startsWith(lowerNeedle) }
            ?.let { return LabelMatch(it.first, Confidence.HIGH) }

        // LOW: broader substring matches — confirm before committing
        labeled.firstOrNull { it.second.contains(lowerNeedle) }
            ?.let { return LabelMatch(it.first, Confidence.LOW) }
        labeled.firstOrNull { label ->
            // Label must be ≥4 chars to avoid spurious matches on short names
            label.second.length >= 4 && lowerNeedle.contains(label.second)
        }?.let { return LabelMatch(it.first, Confidence.LOW) }

        return null
    }

    /** First installed package whose name contains [needle] as a dotted segment. */
    private fun findByPackage(needle: String): String? {
        val activities = launchers().map { it.first }
        val lowerNeedle = needle.lowercase().replace(" ", "")
        if (lowerNeedle.length < 3) return null
        return activities
            .map { it.activityInfo.packageName }
            .firstOrNull { pkg ->
                pkg.split('.').any { it.equals(lowerNeedle, ignoreCase = true) }
            }
    }
}
