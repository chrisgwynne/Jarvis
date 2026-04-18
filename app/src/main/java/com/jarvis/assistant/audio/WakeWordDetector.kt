package com.jarvis.assistant.audio

/**
 * WakeWordDetector — interface for all wake-word detection backends.
 *
 * Implementations:
 *   [GoogleWakeWordDetector] — Android SpeechRecognizer (free, requires internet unless
 *                              the device has an on-device model).
 *   [TFLiteWakeWordDetector] — openWakeWord TFLite model (fully offline, low battery).
 *
 * Both share the same start/stop contract so JarvisRuntime is backend-agnostic.
 */
interface WakeWordDetector {
    /** Start the detection loop. No-op if already running. */
    fun start()

    /** Stop detection immediately. Safe to call multiple times. */
    fun stop()
}
