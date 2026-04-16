package com.jarvis.assistant.tools.device

import android.Manifest
import android.content.Context
import android.util.Log
import com.jarvis.assistant.audio.recording.AudioRecordingManager
import com.jarvis.assistant.audio.recording.RecordingFileStore
import com.jarvis.assistant.audio.recording.RecordingResult
import com.jarvis.assistant.audio.recording.RecordingState
import com.jarvis.assistant.audio.recording.RecordingTranscriber
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AudioRecordingTool — handles all voice recording commands in one tool.
 *
 * ACTIONS (selected by pattern matching → params["action"]):
 *   start      — open MediaRecorder, begin ambient mic recording
 *   stop       — finalize recording, save M4A file to app storage
 *   transcribe — send latest file to OpenAI Whisper → return plain transcript
 *   summarize  — transcribe + return ToolResult.Augmented so the LLM summarizes it
 *
 * ONE TOOL FOR ALL ACTIONS:
 *   This keeps [AudioRecordingManager] as a single shared instance. If start/stop
 *   were separate tools each holding their own manager, state would be inconsistent.
 *
 * ORDERING IN REGISTRY:
 *   SUMMARIZE is matched before TRANSCRIBE because "summarize the last recording"
 *   could also partially match a transcription pattern. More specific first.
 *
 * MIC CONFLICT NOTE:
 *   On Android 10+ (API 29+), multiple concurrent mic captures are permitted by the OS.
 *   The wake-word detector and this tool's MediaRecorder can share the mic simultaneously.
 *   On API 26-28, the device may reject the second capture; start() returns Failure in
 *   that case and the user is informed cleanly.
 */
class AudioRecordingTool(
    private val context: Context,
    private val recordingManager: AudioRecordingManager,
    private val transcriber: RecordingTranscriber
) : Tool {

    override val name        = "audio_recording"
    override val description = "Records, transcribes, and summarizes audio"
    // RECORD_AUDIO is already held by the foreground service, but ToolRegistry's
    // permission gate still validates it so the error path is exercised correctly.
    override val requiredPermissions = listOf(Manifest.permission.RECORD_AUDIO)

    companion object {
        private const val TAG = "AudioRecordingTool"

        // SUMMARIZE must come before TRANSCRIBE in matches() to prevent false positives
        private val SUMMARIZE_TRIGGERS = Regex(
            """summarize\s+(?:the\s+)?(?:last\s+|latest\s+)?recording""" +
            """|what\s+was\s+(?:the\s+)?recording\s+about""" +
            """|recap\s+(?:the\s+)?recording""",
            RegexOption.IGNORE_CASE
        )
        private val TRANSCRIBE_TRIGGERS = Regex(
            """transcribe(?:\s+(?:the\s+)?(?:last\s+|latest\s+)?recording)?""" +
            """|what\s+did\s+i\s+say""" +
            """|convert\s+(?:the\s+)?recording\s+to\s+text""",
            RegexOption.IGNORE_CASE
        )
        private val STOP_TRIGGERS = Regex(
            """(?:stop|end|finish)\s+(?:the\s+)?(?:recording|voice\s+note|memo)""",
            RegexOption.IGNORE_CASE
        )
        private val START_TRIGGERS = Regex(
            """(?:start|begin)\s+(?:a\s+)?(?:recording|voice\s+note|memo|audio)""" +
            """|record\s+(?:this|a\s+voice\s+note|a\s+memo|a\s+note|(?:the\s+)?conversation|everything)""" +
            """|(?:start|begin)\s+recording\s+(?:the\s+)?(?:conversation|this|us)""",
            RegexOption.IGNORE_CASE
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        return when {
            SUMMARIZE_TRIGGERS.containsMatchIn(t)  -> ToolInput(t, mapOf("action" to "summarize"))
            TRANSCRIBE_TRIGGERS.containsMatchIn(t) -> ToolInput(t, mapOf("action" to "transcribe"))
            STOP_TRIGGERS.containsMatchIn(t)       -> ToolInput(t, mapOf("action" to "stop"))
            START_TRIGGERS.containsMatchIn(t)      -> ToolInput(t, mapOf("action" to "start"))
            else                                   -> null
        }
    }

    override suspend fun execute(input: ToolInput): ToolResult =
        when (input.param("action")) {
            "start"      -> handleStart()
            "stop"       -> handleStop()
            "transcribe" -> handleTranscribe(summarize = false)
            "summarize"  -> handleTranscribe(summarize = true)
            else         -> ToolResult.Failure("Unknown recording action.")
        }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private suspend fun handleStart(): ToolResult = withContext(Dispatchers.IO) {
        when (val result = recordingManager.start()) {
            is RecordingResult.Started          -> ToolResult.Success("Recording started.")
            is RecordingResult.AlreadyRecording -> ToolResult.Failure("Already recording.")
            is RecordingResult.Failure          ->
                ToolResult.Failure("Couldn't start recording. ${result.reason.take(60)}")
            else -> ToolResult.Failure("Unexpected recording state.")
        }
    }

    private suspend fun handleStop(): ToolResult = withContext(Dispatchers.IO) {
        when (val result = recordingManager.stop()) {
            is RecordingResult.Stopped    -> {
                val secs = result.durationMs / 1_000
                ToolResult.Success("Recording stopped. Saved $secs seconds of audio.")
            }
            RecordingResult.NotRecording ->
                ToolResult.Failure("No recording is active.")
            is RecordingResult.Failure   ->
                ToolResult.Failure("Error stopping the recording.")
            else -> ToolResult.Failure("Unexpected recording state.")
        }
    }

    /**
     * Transcribe the latest recording file.
     * If a recording is currently in progress, stop it first.
     * [summarize] = true → wrap transcript in an LLM-summarisation request via
     *   ToolResult.Augmented, which the pipeline feeds to callLlm() instead of
     *   the original transcript.
     */
    private suspend fun handleTranscribe(summarize: Boolean): ToolResult {
        // If actively recording, stop before transcribing
        if (recordingManager.state == RecordingState.RECORDING) {
            Log.d(TAG, "Stopping active recording before transcribing")
            withContext(Dispatchers.IO) { recordingManager.stop() }
        }

        val file = withContext(Dispatchers.IO) {
            RecordingFileStore.getLatestRecording(context)
        } ?: return ToolResult.Failure("No recordings found. Start a recording first.")

        Log.d(TAG, "Transcribing ${file.name} (summarize=$summarize)")

        return try {
            val transcript = withContext(Dispatchers.IO) { transcriber.transcribe(file) }
            Log.d(TAG, "Transcript (${transcript.length} chars): ${transcript.take(80)}…")

            if (summarize) {
                // Feed the full transcript to the LLM as the new query.
                // The pipeline's callLlm() will produce a spoken summary.
                ToolResult.Augmented(
                    "Summarize this recording transcript in 2-3 sentences. Transcript: $transcript"
                )
            } else {
                // Speak the transcript directly (truncated for voice output)
                val spoken = if (transcript.length > 400)
                    transcript.take(400) + "… (transcript continues)"
                else
                    transcript
                ToolResult.Success(spoken)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Transcription error: ${e.message}")
            ToolResult.Failure(e.message?.take(100) ?: "Transcription failed.")
        }
    }
}
