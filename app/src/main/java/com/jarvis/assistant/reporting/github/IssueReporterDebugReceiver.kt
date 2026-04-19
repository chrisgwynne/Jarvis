package com.jarvis.assistant.reporting.github

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * IssueReporterDebugReceiver — owner/dev-only manual test trigger.
 *
 * Fires [IssueReporter.sendTestIssue] when the app receives:
 *
 *     adb shell am broadcast \
 *         -n com.jarvis.assistant/.reporting.github.IssueReporterDebugReceiver \
 *         -a com.jarvis.assistant.DEBUG_TEST_ISSUE
 *
 * Guarded two ways so it can't be abused in a release build that the feature
 * flag is accidentally enabled in:
 *
 *   1. The receiver is declared `exported="false"` in the manifest, so only
 *      the shell user (adb) on the device can target it — other apps cannot.
 *   2. At runtime we additionally reject the intent unless the APK was built
 *      with the FLAG_DEBUGGABLE bit set.  Release APKs (Play/F-Droid builds)
 *      do not have that flag, so this receiver becomes a permanent no-op in
 *      production.
 *
 * No UI changes required — everything lives behind this one hook.
 */
class IssueReporterDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val appInfo = context.applicationInfo
        val debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.w(TAG, "Ignored DEBUG_TEST_ISSUE broadcast in non-debuggable build")
            return
        }
        val reporter = IssueReporter.get() ?: run {
            Log.w(TAG, "IssueReporter not installed yet — ignoring test broadcast")
            return
        }
        Log.i(TAG, "Manual test issue requested")
        reporter.sendTestIssue { outcome ->
            Log.i(TAG, "Test issue outcome: $outcome")
        }
    }

    companion object {
        private const val TAG = "IssueReporterDebug"
        const val ACTION = "com.jarvis.assistant.DEBUG_TEST_ISSUE"
    }
}
