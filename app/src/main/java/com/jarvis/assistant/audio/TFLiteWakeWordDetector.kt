package com.jarvis.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * TFLiteWakeWordDetector — fully offline wake-word detection via an openWakeWord TFLite model.
 *
 * SETUP:
 *   1. Download a pre-trained openWakeWord model for "hey jarvis" (or similar) from:
 *      https://github.com/dscripka/openWakeWord/releases
 *      The file should be an INT8-quantised TFLite model (~1–2 MB).
 *   2. Place it at: app/src/main/assets/wakeword.tflite
 *   3. Switch JarvisRuntime to use this detector (set `useOfflineWakeWord = true` in Settings).
 *
 * MODEL CONTRACT:
 *   Input:  [1 × FRAME_SIZE] float32 — one audio frame of 16 kHz mono PCM, normalised to [-1, 1]
 *   Output: [1 × 1] float32 — detection score in [0, 1]; threshold 0.5 means "wake word detected"
 *
 * AUDIO:
 *   Reads from AudioRecord at 16 kHz, 16-bit mono. Each 96 ms frame (1536 samples) is passed
 *   to the model. Runs on Dispatchers.Default (background thread pool).
 *
 * BATTERY:
 *   Significantly more battery-efficient than SpeechRecognizer because the model runs on the
 *   CPU (no Google servers), processes small frames, and uses no network.
 *
 * FALLBACK:
 *   If the model asset is missing, [start] logs a warning and falls back gracefully.
 *   The caller should detect this and fall back to [GoogleWakeWordDetector].
 */
class TFLiteWakeWordDetector(
    private val context: Context,
    private val onDetected: () -> Unit,
    private val onError: (String) -> Unit = {}
) : WakeWordDetector {

    companion object {
        private const val TAG             = "TFLiteWakeWordDetector"
        private const val MODEL_ASSET     = "wakeword.tflite"
        private const val SAMPLE_RATE     = 16_000
        private const val FRAME_SAMPLES   = 1_536       // 96 ms at 16 kHz
        private const val DETECT_THRESHOLD = 0.5f
        private const val COOLDOWN_MS     = 1_500L      // ignore re-triggers for 1.5 s after detection
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Returns true if the model asset is present and the detector can be used. */
    val isAvailable: Boolean
        get() = try {
            context.assets.open(MODEL_ASSET).use { true }
        } catch (_: Exception) { false }

    override fun start() {
        if (job?.isActive == true) return
        if (!isAvailable) {
            Log.w(TAG, "$MODEL_ASSET not found — falling back to Google detector")
            onError("Model asset missing: $MODEL_ASSET")
            return
        }
        Log.d(TAG, "Starting TFLite wake-word detection")
        job = scope.launch { runDetectionLoop() }
    }

    override fun stop() {
        Log.d(TAG, "Stopping TFLite wake-word detection")
        job?.cancel()
        job = null
    }

    // ── Detection loop ────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun runDetectionLoop() {
        val interpreter = loadModel() ?: run {
            Log.e(TAG, "Failed to load TFLite model — aborting")
            onError("Failed to load $MODEL_ASSET")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, FRAME_SAMPLES * 2)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord: ${e.message}")
            interpreter.close()
            onError("AudioRecord init failed")
            return
        }

        val pcmBuffer   = ShortArray(FRAME_SAMPLES)
        val inputTensor  = ByteBuffer.allocateDirect(FRAME_SAMPLES * 4).order(ByteOrder.nativeOrder())
        val outputTensor = Array(1) { FloatArray(1) }
        var lastDetectionMs = 0L

        try {
            recorder.startRecording()
            Log.d(TAG, "AudioRecord started — listening for wake word")

            while (scope.isActive) {
                val read = recorder.read(pcmBuffer, 0, FRAME_SAMPLES)
                if (read <= 0) continue

                inputTensor.rewind()
                for (i in 0 until read) {
                    inputTensor.putFloat(pcmBuffer[i] / 32768f)
                }

                interpreter.run(inputTensor, outputTensor)
                val score = outputTensor[0][0]

                val now = System.currentTimeMillis()
                if (score >= DETECT_THRESHOLD && now - lastDetectionMs > COOLDOWN_MS) {
                    lastDetectionMs = now
                    Log.d(TAG, "Wake word detected (score=${"%.2f".format(score)}) — firing callback")
                    // Switch to Main to call onDetected (matches GoogleWakeWordDetector behaviour)
                    kotlinx.coroutines.withContext(Dispatchers.Main) { onDetected() }
                    // Stop after detection — JarvisRuntime will call start() again
                    return
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
            interpreter.close()
        }
    }

    private fun loadModel(): Interpreter? = try {
        val afd        = context.assets.openFd(MODEL_ASSET)
        val inputStream = FileInputStream(afd.fileDescriptor)
        val channel     = inputStream.channel
        val modelBuffer = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        channel.close()
        inputStream.close()
        Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(1) })
    } catch (e: Exception) {
        Log.e(TAG, "loadModel failed: ${e.message}")
        null
    }
}
