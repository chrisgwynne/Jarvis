package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches WhatsApp for the conversation window to appear (after we launch it via
 * `whatsapp://send`) and automatically taps the Send button exactly once.
 *
 * Retry strategy: WhatsApp populates the compose box asynchronously after the
 * window opens, so the Send button can be briefly disabled.  We attempt on every
 * relevant event for up to ARMED_TIMEOUT_MS, then auto-disarm.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisA11y"

        // All known WhatsApp send-button resource IDs across versions
        private val SEND_BUTTON_IDS = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_btn",
            "com.whatsapp:id/send_button",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp.w4b:id/send_btn",
            "com.whatsapp.w4b:id/send_button"
        )

        // How long to stay armed before giving up (10 s)
        private const val ARMED_TIMEOUT_MS = 10_000L

        private val armed = AtomicBoolean(false)

        fun arm() {
            armed.set(true)
            Log.d(TAG, "Armed for auto-send")
        }

        fun disarm() {
            if (armed.compareAndSet(true, false)) Log.d(TAG, "Disarmed")
        }

        fun isArmed(): Boolean = armed.get()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val disarmRunnable = Runnable {
        if (armed.compareAndSet(true, false)) Log.w(TAG, "Auto-disarmed after timeout")
    }

    override fun onServiceConnected() {
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            handleEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling event", e)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        if (!armed.get()) return

        val pkgName = event.packageName?.toString() ?: return
        if (pkgName != "com.whatsapp" && pkgName != "com.whatsapp.w4b") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // New WhatsApp window opened — schedule timeout and attempt immediately
                mainHandler.removeCallbacks(disarmRunnable)
                mainHandler.postDelayed(disarmRunnable, ARMED_TIMEOUT_MS)
                trySend(pkgName)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content updated (compose text populated) — attempt again
                trySend(pkgName)
            }
        }
    }

    private fun trySend(pkgName: String) {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "rootInActiveWindow null, will retry on next event")
            return
        }

        val sendButton = findSendButton(root)
        if (sendButton == null) {
            Log.d(TAG, "Send button not found yet (may not be enabled)")
            return
        }

        if (armed.compareAndSet(true, false)) {
            mainHandler.removeCallbacks(disarmRunnable)
            Log.d(TAG, "Tapping Send in $pkgName")
            sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Interrupted")
    }

    // ── node search ───────────────────────────────────────────────────────────

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. Known resource IDs
        for (id in SEND_BUTTON_IDS) {
            val candidate = root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isEnabled && it.isClickable }
            if (candidate != null) return candidate
        }

        // 2. Text search ("Send" button label on some WhatsApp locales)
        val byText = root.findAccessibilityNodeInfosByText("Send")
            .firstOrNull { it.isEnabled && it.isClickable }
        if (byText != null) return byText

        // 3. Recursive content-description search — handles icon-only send buttons
        return findByContentDesc(root, "Send")
    }

    /**
     * Walk the view tree looking for a clickable, enabled node whose content
     * description contains [desc] (case-insensitive).  WhatsApp's send button
     * is often an ImageButton with no text — only a content description.
     */
    private fun findByContentDesc(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val cd = node.contentDescription?.toString() ?: ""
        if (cd.contains(desc, ignoreCase = true) && node.isEnabled && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByContentDesc(child, desc)
            if (result != null) return result
        }
        return null
    }
}
