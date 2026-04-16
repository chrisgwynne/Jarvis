package com.jarvis.assistant.util

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * LatencyTracker — lightweight pipeline timing for the Jarvis voice assistant.
 *
 * Usage:
 *   LatencyTracker.pipelineStart()          // call at wake-word detection
 *   LatencyTracker.mark("STT_COMPLETE")     // call after each stage
 *   LatencyTracker.reset()                  // call at end of pipeline (optional)
 *
 * All output goes to Logcat tag "JarvisLatency" at INFO level.
 * Filter with: adb logcat -s JarvisLatency
 *
 * Marks (in order of pipeline execution):
 *   WAKE_DETECTED        — wake word fired, pipeline beginning
 *   STT_COMPLETE         — transcript received from speech capture
 *   FLOW_CHECKED         — FollowUpCoordinator.process() returned
 *   INTENT_CLASSIFIED    — IntentClassifier.classify() returned
 *   TOOL_MATCHED         — ToolRegistry.match() returned a result
 *   POLICY_EVALUATED     — ActionPolicyGate.evaluate() returned
 *   TOOL_EXECUTED        — tool.execute() returned
 *   LLM_REQUEST_START    — about to call LlmRouter
 *   LLM_COMPLETE         — LLM response string received
 *   TTS_START            — TtsEngine.speak() called
 *   PIPELINE_DONE        — speakAndRecord() completed
 */
object LatencyTracker {
    private const val TAG = "JarvisLatency"
    private val startMs = AtomicLong(0L)

    fun pipelineStart() {
        val now = System.currentTimeMillis()
        startMs.set(now)
        Log.i(TAG, "[PIPELINE_START] t=0ms")
    }

    fun mark(label: String) {
        val start = startMs.get()
        if (start == 0L) return  // pipeline not started — skip
        val elapsed = System.currentTimeMillis() - start
        Log.i(TAG, "[$label] ${elapsed}ms")
    }

    fun reset() {
        startMs.set(0L)
    }
}
