package com.jarvis.assistant.reporting.github

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * AutoReportingExceptionHandler — coroutine-level "auto-issue" handler.
 *
 * `JarvisUncaughtHandler` only catches Java/Kotlin uncaught exceptions on the
 * thread default handler.  Coroutines that fail without a handler installed
 * route through `Thread.UncaughtExceptionHandler.uncaughtException` only when
 * the failing job is a root job — children of a SupervisorJob (which is what
 * `JarvisRuntime.scope` and `JarvisService.serviceScope` both use) silently
 * log to System.err and never reach the uncaught handler.
 *
 * The result was that a misbehaving coroutine in the runtime — say a Room
 * write that throws because the DAO contract changed — would just print to
 * logcat and never surface to the operator.  This handler closes that gap by
 * funnelling any uncaught coroutine exception through [IssueReporter] at HIGH
 * severity (so the rate limiter + repetition gate still apply, and a one-off
 * transient blip never becomes a noisy issue).
 *
 * Usage:
 *   val scope = CoroutineScope(
 *       SupervisorJob() + Dispatchers.Main + autoReporting("subsystem-name")
 *   )
 *
 * The [subsystem] tag is the IssueReporter "subsystem" field — keep it short
 * (one word is fine) so issues group cleanly: "runtime", "service",
 * "knowledge", etc.
 */
fun autoReporting(subsystem: String): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        // Logcat first — useful even when the reporter is disabled / no token.
        Log.e("JarvisAutoReport", "[$subsystem] uncaught coroutine exception", throwable)

        // Reporter is a singleton — get() is null until IssueReporter.install
        // has run from JarvisApp.onCreate.  The null branch is normal during
        // process death / shutdown sequences.
        val reporter = IssueReporter.get() ?: return@CoroutineExceptionHandler
        try {
            reporter.reportHigh(
                subsystem = subsystem,
                category  = "UNCAUGHT_COROUTINE",
                message   = "${throwable.javaClass.simpleName}: ${throwable.message ?: "(no message)"}",
                throwable = throwable,
                metadata  = mapOf(
                    "thread" to Thread.currentThread().name,
                ),
            )
        } catch (t: Throwable) {
            // Reporter must not turn one failure into two.
            Log.e("JarvisAutoReport", "reporter raised while reporting", t)
        }
    }
