package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * ScreenInspector — converts the live AccessibilityNodeInfo tree into an
 * immutable [ScreenSnapshot] and (optionally) attaches a PNG screenshot.
 *
 * Threading: must be invoked from the AccessibilityService process — node
 * info objects are bound to that thread.  All public functions are suspend
 * so the screenshot await is non-blocking.
 *
 * Why bound to the service?  AccessibilityNodeInfo has a window-scoped
 * lifetime and recycle() / refresh() must run on the binder thread the
 * service lives on.  Cross-process callers go through
 * [JarvisAccessibilityService] which marshals back here.
 */
object ScreenInspector {

    private const val TAG = "ScreenInspector"
    private const val SCREENSHOT_TIMEOUT_MS = 1_500L
    private const val SCREENSHOT_QUALITY = 75
    /** Cap individual node labels so a runaway TextView can't blow up the snapshot. */
    private const val MAX_TEXT_PER_NODE = 200
    /** Cap total node count — anything past this is past prompt budget anyway. */
    private const val MAX_NODES = 250

    /**
     * Single-thread executor reused across screenshot calls.  takeScreenshot
     * needs an Executor to deliver its callback on; allocating a fresh one
     * per call would leak a thread on every tap-or-read flow because
     * newSingleThreadExecutor() never auto-shuts-down without explicit
     * shutdown(). One shared instance is enough — screenshots are serialised
     * by the SCREENSHOT_TIMEOUT_MS gate above.
     */
    private val screenshotExecutor: java.util.concurrent.Executor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "JarvisA11yScreenshot").apply { isDaemon = true }
        }

    /**
     * Build a [ScreenSnapshot] for the foreground window of [service].
     *
     * @param withScreenshot when true and API ≥ 30, attaches a PNG of the
     *   default display.  Costs ~150 ms; skip for "tap by label" flows that
     *   only need the text tree.
     */
    suspend fun snapshot(
        service: AccessibilityService,
        withScreenshot: Boolean = true
    ): ScreenSnapshot? {
        val root: AccessibilityNodeInfo = service.rootInActiveWindow
            ?: return null.also { Log.d(TAG, "rootInActiveWindow is null") }

        val foregroundPackage = root.packageName?.toString()
        val nodes = mutableListOf<ScreenNode>()
        flatten(root, nodes)

        val screenshot: String? =
            if (withScreenshot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshotPng(service)
            } else null

        return ScreenSnapshot(
            foregroundPackage   = foregroundPackage,
            textTree            = nodes,
            screenshotPngBase64 = screenshot
        )
    }

    // ── Tree flattening ──────────────────────────────────────────────────────

    private fun flatten(node: AccessibilityNodeInfo, out: MutableList<ScreenNode>) {
        if (out.size >= MAX_NODES) return

        val text = node.text?.toString()?.take(MAX_TEXT_PER_NODE).orEmpty()
        val cd   = node.contentDescription?.toString()?.take(MAX_TEXT_PER_NODE).orEmpty()
        val keep = text.isNotBlank() || cd.isNotBlank() ||
                   node.isClickable || node.isLongClickable || node.isEditable

        if (keep) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out += ScreenNode(
                index               = out.size,
                text                = text,
                contentDescription  = cd,
                viewId              = node.viewIdResourceName.orEmpty(),
                className           = node.className?.toString().orEmpty(),
                isClickable         = node.isClickable,
                isLongClickable     = node.isLongClickable,
                isEditable          = node.isEditable,
                isEnabled           = node.isEnabled,
                boundsLeft          = rect.left,
                boundsTop           = rect.top,
                boundsRight         = rect.right,
                boundsBottom        = rect.bottom
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            flatten(child, out)
        }
    }

    // ── Screenshot path (API 30+) ────────────────────────────────────────────

    private suspend fun takeScreenshotPng(service: AccessibilityService): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
            val bitmap: Bitmap? = suspendCancellableCoroutine { cont ->
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        screenshotExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                try {
                                    val hardware = result.hardwareBuffer
                                    val color    = result.colorSpace
                                    val bmp      = Bitmap.wrapHardwareBuffer(hardware, color)
                                    // Copy to a software bitmap so we can compress; close the buffer either way.
                                    val sw = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                                    try { hardware.close() } catch (_: Throwable) {}
                                    try { bmp?.recycle() }   catch (_: Throwable) {}
                                    if (cont.isActive) cont.resume(sw)
                                } catch (e: Throwable) {
                                    Log.w(TAG, "takeScreenshot callback failed: ${e.message}")
                                    if (cont.isActive) cont.resume(null)
                                }
                            }
                            override fun onFailure(errorCode: Int) {
                                Log.w(TAG, "takeScreenshot failed with errorCode: $errorCode")
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "takeScreenshot threw: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
            bitmap?.let { bmp ->
                try {
                    val out = ByteArrayOutputStream()
                    // PNG keeps text crisp for the vision model.
                    bmp.compress(Bitmap.CompressFormat.PNG, SCREENSHOT_QUALITY, out)
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                } finally {
                    bmp.recycle()
                }
            }
        }
    }
}
