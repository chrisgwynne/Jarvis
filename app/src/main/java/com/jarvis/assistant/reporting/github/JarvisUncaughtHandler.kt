package com.jarvis.assistant.reporting.github

import android.util.Log

/**
 * JarvisUncaughtHandler — chains onto the thread-default uncaught-exception
 * handler so that a crash is serialised to disk BEFORE we hand back to the
 * OS default (which kills the process).
 *
 * Why not post to GitHub directly on the crash path?  The process is dying.
 * Any network call would race the VM teardown; OkHttp coroutines won't
 * reliably complete.  Instead we write a minimal structured record to
 * [PendingReportStore] synchronously, then chain.  On the next cold start,
 * [IssueReporter.drainPending] picks it up and files the issue from a
 * healthy process.
 *
 * Crashes are always FATAL — they get through the repetition gate on first
 * occurrence but are still deduped on the per-fingerprint cooldown, so a
 * tight crash loop doesn't fire a hundred identical issues.
 */
internal object JarvisUncaughtHandler {

    private const val TAG = "JarvisUncaughtHandler"

    fun install(reporter: IssueReporter) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Build a synthetic fingerprint + persisted record and write it
                // synchronously.  Every failure path here is swallowed so we
                // never compound a crash into another crash.
                val report = IssueReport(
                    severity  = ErrorSeverity.FATAL,
                    subsystem = "service",
                    category  = "UNCAUGHT_EXCEPTION",
                    message   = "Uncaught ${throwable.javaClass.simpleName}: ${throwable.message ?: "(no message)"}",
                    throwable = throwable,
                    metadata  = mapOf("thread" to thread.name),
                    isCrash   = true
                )
                reporter.enqueueCrashReport(report)
            } catch (t: Throwable) {
                // Absolutely must not loop into another crash.
                Log.e(TAG, "uncaught-handler lost a crash while trying to record it", t)
            }
            // Chain to whatever the platform default was (usually the
            // Android RuntimeInit handler that logs + dies cleanly).
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "installed")
    }
}
