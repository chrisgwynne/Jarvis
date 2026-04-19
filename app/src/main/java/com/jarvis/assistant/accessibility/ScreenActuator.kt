package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ScreenActuator — performs taps / scrolls / text input against the live
 * accessibility node tree.  Pairs with [ScreenInspector]: callers identify
 * a target by [ScreenNode.index] in a snapshot, then ask the actuator to
 * perform an action against the same coordinates.
 *
 * Why coordinates rather than refreshing nodes?  The snapshot has already
 * walked the tree once and the alternative — keeping live AccessibilityNodeInfo
 * references on the call site — is fragile (the OS recycles them across
 * windows).  Coordinates are durable for the few hundred milliseconds
 * between snapshot and tap.
 */
object ScreenActuator {

    private const val TAG = "ScreenActuator"
    private const val TAP_DURATION_MS = 60L

    /**
     * Perform a click on the node at [node.center].  Falls back to a synthesised
     * tap gesture when the node itself doesn't accept ACTION_CLICK (icon-only
     * buttons in some apps respond to gestures only).
     */
    fun click(service: AccessibilityService, node: ScreenNode): Boolean {
        // Prefer ACTION_CLICK on the live node when we can find one with a
        // matching viewId — semantic clicks beat synthesised gestures, which
        // can land in scroll containers.
        val live = findLiveNode(service, node)
        if (live != null && live.isClickable &&
            live.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "Clicked via ACTION_CLICK on ${node.matchLabel}")
            return true
        }
        return tapAt(service, node.centerX, node.centerY)
    }

    /** Synthesised tap at absolute screen coordinates. */
    fun tapAt(service: AccessibilityService, x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
            .also { Log.d(TAG, "tapAt($x,$y) dispatched=$it") }
    }

    /**
     * Set the text on the editable node represented by [node].  Returns false
     * when the node isn't editable or we can't locate it on the live tree.
     */
    fun setText(service: AccessibilityService, node: ScreenNode, text: String): Boolean {
        val live = findLiveNode(service, node) ?: return false
        if (!live.isEditable) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            .also { Log.d(TAG, "setText on ${node.matchLabel} → $it") }
    }

    /** Walk the live tree looking for a node with the same viewId or label. */
    private fun findLiveNode(
        service: AccessibilityService,
        target: ScreenNode
    ): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        if (target.viewId.isNotBlank()) {
            root.findAccessibilityNodeInfosByViewId(target.viewId)
                .firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        }
        val needle = target.matchLabel
        if (needle.isNotBlank()) {
            root.findAccessibilityNodeInfosByText(needle)
                .firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        }
        return null
    }
}
