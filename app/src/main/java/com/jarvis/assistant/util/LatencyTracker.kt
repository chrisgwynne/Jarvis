package com.jarvis.assistant.util

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * LatencyTracker — lightweight pipeline timing for the Jarvis voice assistant.
 *
 * Two timers per pipeline run:
 *   • startMs    — wall clock at wake-word detection.
 *   • previousMs — wall clock at the most recent mark (for stage duration).
 *
 * Each `mark(label)` call logs both:
 *   • Cumulative time since pipeline start  (`label total=Xms`)
 *   • Time since the previous mark            (`stage=Yms`)
 *
 * The previous shape only logged cumulative time, which made it hard to spot
 * which single stage owned a regression — when the LLM call jumps from 800 ms
 * to 1.4 s you want to see that delta isolated, not buried in cumulative.
 *
 * Usage:
 *   LatencyTracker.pipelineStart()          // call at wake-word detection
 *   LatencyTracker.mark("STT_COMPLETE")     // call after each stage
 *   LatencyTracker.mark("LLM_FIRST_TOKEN")  // …
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
 *   LLM_FIRST_TOKEN      — first streamed token observed
 *   LLM_STREAM_END       — final streamed token observed
 *   LLM_COMPLETE         — LLM response string received (non-streaming)
 *   TTS_START            — TtsEngine.speak() called
 *   PIPELINE_DONE        — speakAndRecord() completed
 *
 * Budget: TARGET_FIRST_TOKEN_MS is the Phase-4 goal for end-of-speech to
 * first audible response token.  When LLM_FIRST_TOKEN exceeds this, the
 * tracker logs a WARN-level breach.
 */
object LatencyTracker {
    private const val TAG = "JarvisLatency"

    /** Phase 4 target — see Phase 4 in the original task spec. */
    const val TARGET_FIRST_TOKEN_MS = 400L

    private val startMs    = AtomicLong(0L)
    private val previousMs = AtomicLong(0L)

    fun pipelineStart() {
        val now = System.currentTimeMillis()
        startMs.set(now)
        previousMs.set(now)
        Log.i(TAG, "[PIPELINE_START] t=0ms")
    }

    fun mark(label: String) {
        val start = startMs.get()
        if (start == 0L) return  // pipeline not started — skip
        val now      = System.currentTimeMillis()
        val total    = now - start
        val stage    = now - previousMs.getAndSet(now)
        Log.i(TAG, "[$label] total=${total}ms stage=${stage}ms")
        if (label == "LLM_FIRST_TOKEN" && total > TARGET_FIRST_TOKEN_MS) {
            Log.w(
                TAG,
                "[BUDGET_BREACH] LLM_FIRST_TOKEN ${total}ms exceeds " +
                    "${TARGET_FIRST_TOKEN_MS}ms target",
            )
        }
    }

    fun reset() {
        startMs.set(0L)
        previousMs.set(0L)
    }
}
