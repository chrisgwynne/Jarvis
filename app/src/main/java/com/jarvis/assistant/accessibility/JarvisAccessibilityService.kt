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

        // How long to stay armed before giving up.  Tightened to 3 s — past
        // that point WhatsApp is either visibly stuck or the user has moved
        // on; either way blocking longer just risks a stale click on a
        // future foreground app.
        private const val ARMED_TIMEOUT_MS = 3_000L

        private val armed = AtomicBoolean(false)
        /**
         * Package the [armed] flag applies to.  Click attempts on any other
         * package are ignored as a safety net so a randomly-foregrounded app
         * can never have its "Send"-labelled button tapped on the user's
         * behalf.  Set by [arm], cleared by [disarm].
         */
        @Volatile private var armedPackage: String? = null

        /**
         * Arm auto-send.  When [pkg] is set, only that package can be clicked
         * — defaults to "com.whatsapp" / "com.whatsapp.w4b" since those are
         * the only packages this service was designed for.
         */
        fun arm(pkg: String = "com.whatsapp") {
            armed.set(true)
            armedPackage = pkg
            Log.d(TAG, "[WA_AUTOSEND_PENDING_CREATED] pkg=$pkg")
        }

        fun disarm() {
            if (armed.compareAndSet(true, false)) {
                armedPackage = null
                Log.d(TAG, "Disarmed")
            }
        }

        fun isArmed(): Boolean = armed.get()

        /**
         * Single-instance handle used by [com.jarvis.assistant.tools.device.ReadScreenTool]
         * and [com.jarvis.assistant.tools.device.TapScreenTool] to reach the live
         * AccessibilityService from outside its process.  Set in onServiceConnected,
         * cleared in onUnbind.
         *
         * Volatile because tools read it from arbitrary coroutine dispatchers.
         */
        @Volatile
        private var instance: JarvisAccessibilityService? = null

        /** True when the user has actually granted accessibility access. */
        fun isConnected(): Boolean = instance != null

        /** Capture a snapshot of the current foreground screen, or null if not connected. */
        suspend fun snapshot(withScreenshot: Boolean = true): ScreenSnapshot? {
            val svc = instance ?: return null
            return ScreenInspector.snapshot(svc, withScreenshot)
        }

        /** Perform a click on [node] from a previously captured [snapshot]. */
        fun click(node: ScreenNode): Boolean {
            val svc = instance ?: return false
            return ScreenActuator.click(svc, node)
        }

        /** Set text on the editable node represented by [node]. */
        fun setText(node: ScreenNode, text: String): Boolean {
            val svc = instance ?: return false
            return ScreenActuator.setText(svc, node, text)
        }

        /**
         * Bridge for tools that need an OS-level global action
         * (e.g. [com.jarvis.assistant.tools.device.ScreenshotTool] calling
         * GLOBAL_ACTION_TAKE_SCREENSHOT).  Returns false when the
         * accessibility service isn't connected.
         */
        fun performGlobalAction(action: Int): Boolean {
            val svc = instance ?: return false
            return svc.performGlobalAction(action)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val disarmRunnable = Runnable {
        if (armed.compareAndSet(true, false)) {
            armedPackage = null
            Log.w(TAG, "[WA_AUTOSEND_TIMEOUT] auto-disarmed after ${ARMED_TIMEOUT_MS}ms")
        }
    }

    override fun onServiceConnected() {
        Log.d(TAG, "Service connected")
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d(TAG, "Service unbinding")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // Belt-and-braces: onUnbind isn't always called on every teardown path.
        instance = null
        super.onDestroy()
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
        // Safety: only act on the package the arm() caller targeted.  Falls
        // back to the WhatsApp pair if armedPackage was somehow lost.
        val expected = armedPackage
        val allowed  = when {
            expected != null -> pkgName == expected
            else             -> pkgName == "com.whatsapp" || pkgName == "com.whatsapp.w4b"
        }
        if (!allowed) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "[WA_ACCESSIBILITY_WINDOW_CHANGED] pkg=$pkgName")
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
            Log.v(TAG, "Send button not found yet (may not be enabled)")
            return
        }
        Log.d(TAG, "[WA_SEND_BUTTON_FOUND] pkg=$pkgName " +
            "id=${sendButton.viewIdResourceName} desc=\"${sendButton.contentDescription}\"")

        if (armed.compareAndSet(true, false)) {
            mainHandler.removeCallbacks(disarmRunnable)
            val ok = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            armedPackage = null
            if (ok) {
                Log.d(TAG, "[WA_SEND_BUTTON_CLICKED] pkg=$pkgName")
                Log.d(TAG, "[WA_AUTOSEND_SUCCESS]")
            } else {
                Log.w(TAG, "[WA_AUTOSEND_FAILED] performAction returned false pkg=$pkgName")
            }
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
