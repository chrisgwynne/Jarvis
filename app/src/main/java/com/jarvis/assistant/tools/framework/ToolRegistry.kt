package com.jarvis.assistant.tools.framework

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.memory.MemoryRetriever
import com.jarvis.assistant.reminders.ReminderRepository
import com.jarvis.assistant.tools.ContactLookup
import com.jarvis.assistant.tools.WebSearch
import com.jarvis.assistant.tools.device.AlarmTool
import com.jarvis.assistant.tools.device.CallTool
import com.jarvis.assistant.tools.device.FlashlightTool
import com.jarvis.assistant.tools.device.MemoryRecallTool
import com.jarvis.assistant.memory.db.JarvisDatabase
import com.jarvis.assistant.shopping.ShoppingRepository
import com.jarvis.assistant.tools.device.CalendarTool
import com.jarvis.assistant.tools.device.DailyBriefingTool
import com.jarvis.assistant.tools.device.HelpTool
import com.jarvis.assistant.tools.device.ImageGenerationTool
import com.jarvis.assistant.tools.device.MediaControlTool
import com.jarvis.assistant.tools.device.MemoryStatsTool
import com.jarvis.assistant.tools.device.OpenAppTool
import com.jarvis.assistant.tools.device.ReadNotificationsTool
import com.jarvis.assistant.tools.device.ShoppingListTool
import com.jarvis.assistant.tools.device.SmsTool
import com.jarvis.assistant.tools.device.TimerTool
import com.jarvis.assistant.tools.device.VolumeTool
import com.jarvis.assistant.tools.device.WhatsAppTool
import com.jarvis.assistant.tools.web.WebSearchTool
import com.jarvis.assistant.tools.web.WeatherTool
import com.jarvis.assistant.location.CurrentLocationProvider
import com.jarvis.assistant.util.SettingsStore
import com.jarvis.assistant.audio.recording.AudioRecordingManager
import com.jarvis.assistant.audio.recording.RecordingState
import com.jarvis.assistant.audio.recording.RecordingTranscriber
import com.jarvis.assistant.camera.CameraCaptureManager
import com.jarvis.assistant.camera.VisionClient
import com.jarvis.assistant.tools.device.AnalyzeCameraViewTool
import com.jarvis.assistant.call.OutgoingCallController
import com.jarvis.assistant.tools.device.AudioRecordingTool
import com.jarvis.assistant.tools.device.CameraCaptureTool
import com.jarvis.assistant.tools.device.EmailTool
import com.jarvis.assistant.tools.device.EndCallTool
import com.jarvis.assistant.tools.smart.SmartHomeTool
import com.jarvis.assistant.tools.web.MusicSearchTool

/**
 * ToolRegistry — ordered list of tools; first match wins.
 *
 * Ordering matters:
 *   High-specificity tools (WhatsApp, Call, SMS) must come before
 *   low-specificity ones (OpenApp, WebSearch) to prevent misrouting.
 */
class ToolRegistry private constructor(
    private val tools: List<Tool>,
    /** Held so [release] can stop an in-progress recording on service teardown. */
    private val recordingManager: AudioRecordingManager? = null
) {

    /**
     * Release resources that must be freed when the service stops.
     *
     * Currently: stops any active [AudioRecordingManager] session so the OS
     * MediaRecorder releases the microphone.  Without this, a recording that is
     * still running when the service is stopped keeps the mic locked and prevents
     * [WakeWordDetector] from starting on the next service launch.
     */
    fun release() {
        recordingManager?.let { rm ->
            if (rm.state == RecordingState.RECORDING) {
                Log.i(TAG, "Service stopping with recording active — stopping recorder to release mic")
                rm.stop()
            }
        }
    }

    companion object {
        private const val TAG = "ToolRegistry"

        /**
         * Build the default registry for production use.
         *
         * @param memoryRetriever  Optional — if provided, [MemoryRecallTool] is
         *   included so Jarvis can answer questions about past conversations.
         */
        fun buildDefault(
            context: Context,
            settings: SettingsStore,
            memoryRetriever: MemoryRetriever? = null,
            reminderRepository: ReminderRepository? = null,
            outgoingCallController: OutgoingCallController? = null,
            locationProvider: CurrentLocationProvider? = null
        ): ToolRegistry {
            val contacts = ContactLookup(context)
            val search   = WebSearch()

            // Shared camera + recording instances — one per registry lifetime
            val cameraCapture    = CameraCaptureManager(context)
            val visionClient     = VisionClient(settings)
            val recordingManager = AudioRecordingManager(context)
            val transcriber      = RecordingTranscriber(settings)

            return ToolRegistry(
                tools = buildList {
                    // End-call MUST precede CallTool so "end call" / "hang up"
                    // is not accidentally routed to the outgoing-call tool.
                    outgoingCallController?.let { add(EndCallTool(it)) }
                    // High-specificity tools first — order matters
                    add(CallTool(context, contacts))
                    add(SmsTool(context, contacts))
                    add(WhatsAppTool(context, contacts))
                    add(EmailTool(context))
                    add(VolumeTool(context))
                    // Music search before MediaControl — "play [track]" must not hit generic play/pause
                    add(MusicSearchTool(context))
                    add(MediaControlTool(context))
                    add(FlashlightTool(context))
                    add(AlarmTool(context))
                    add(TimerTool(context))
                    add(CalendarTool(context))
                    add(SmartHomeTool(settings))
                    // Weather before web search — structured answer, no search cost
                    locationProvider?.let { add(WeatherTool(it)) }
                    // Memory tools before generic open-app so they aren't misrouted
                    val db = JarvisDatabase.getInstance(context)
                    add(MemoryStatsTool(db.memoryDao(), db.memoryFactDao()))
                    memoryRetriever?.let { add(MemoryRecallTool(it)) }
                    add(DailyBriefingTool(context, reminderRepository))
                    add(ImageGenerationTool(context, settings))
                    val shoppingRepo = ShoppingRepository(db.shoppingDao())
                    add(ShoppingListTool(shoppingRepo))
                    add(ReadNotificationsTool(context))
                    // Camera + vision tools (before OpenApp to avoid misrouting)
                    add(CameraCaptureTool(context, cameraCapture))
                    add(AnalyzeCameraViewTool(context, cameraCapture, visionClient))
                    // Audio recording (start/stop/transcribe/summarize)
                    add(AudioRecordingTool(context, recordingManager, transcriber))
                    add(OpenAppTool(context))
                    add(WebSearchTool(search, settings))
                    add(HelpTool { buildCapabilitySummary(settings) })
                },
                recordingManager = recordingManager
            )
        }

        /**
         * Build a spoken capability summary that reflects actual live app state.
         *
         * Evaluated lazily on each "what can you do?" query so it reflects the
         * current provider, OpenClaw state, etc. without a stale hardcoded list.
         */
        private fun buildCapabilitySummary(settings: SettingsStore): String {
            val visionProviders  = setOf("OpenAI", "OpenRouter", "Anthropic")
            val canAnalyseImages = settings.llmProvider in visionProviders
            val canTranscribe    = settings.llmProvider == "OpenAI"

            val caps = buildList {
                add("make calls and end calls")
                add("send SMS and WhatsApp messages")
                add("take photos and selfies")
                if (canAnalyseImages) add("analyse what the camera sees")
                add("set reminders, timers, and alarms")
                add("start and stop audio recordings")
                if (canTranscribe) add("transcribe and summarise recordings")
                add("search the web")
                add("control media playback and volume")
                add("open apps")
                add("read your calendar and notifications")
                add("manage your shopping list")
                add("generate images")
                add("give you a daily briefing")
                if (settings.openClawEnabled) add("route complex tasks to a remote assistant")
            }

            val capList = if (caps.size > 1)
                caps.dropLast(1).joinToString(", ") + ", and ${caps.last()}"
            else
                caps.firstOrNull() ?: "answer questions"

            val caveats = buildList {
                if (!canAnalyseImages)
                    add("Image analysis requires OpenAI, OpenRouter, or Anthropic — switch in Settings.")
                if (!canTranscribe)
                    add("Recording transcription requires OpenAI — switch in Settings.")
            }

            return buildString {
                append("Here's what I can do right now: $capList. ")
                append("Just speak naturally — for example: call Mum, take a selfie, ")
                append("start recording, or remind me at 5 pm to leave work.")
                if (caveats.isNotEmpty()) {
                    append(" Note: ")
                    append(caveats.joinToString(" "))
                }
            }
        }
    }

    /**
     * Find the first tool that matches [transcript] and is available
     * given the current [isOnline] state.
     *
     * Returns a Pair(tool, input) or null if nothing matched.
     */
    fun match(transcript: String, isOnline: Boolean): Pair<Tool, ToolInput>? {
        for (tool in tools) {
            // Skip network tools when offline (unless they have a local fallback)
            if (tool.requiresNetwork && !isOnline && !tool.isLocalFallback) continue

            val input = tool.matches(transcript) ?: continue
            Log.d(TAG, "Matched '${tool.name}' for: \"${transcript.take(60)}\"")
            return Pair(tool, input)
        }
        return null
    }

    /**
     * Execute a matched tool, with permission checking.
     * Returns [ToolResult.Failure] if permissions are missing.
     */
    suspend fun execute(
        context: Context,
        tool: Tool,
        input: ToolInput
    ): ToolResult {
        // Permission gate
        val missing = tool.requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            val label = missing.joinToString(", ") { it.substringAfterLast('.') }
            return ToolResult.Failure(
                spokenFeedback = "I need the $label permission to do that. Please grant it in Settings."
            )
        }

        return try {
            tool.execute(input)
        } catch (e: Exception) {
            Log.e(TAG, "Tool '${tool.name}' threw: ${e.message}", e)
            ToolResult.Failure(
                spokenFeedback = "That didn't work. Let me try another way if you want.",
                cause = e
            )
        }
    }

    /** All registered tool names — for debug/logging. */
    val registeredNames: List<String> get() = tools.map { it.name }

    /** Tools available given the current network state. */
    fun available(isOnline: Boolean): List<Tool> =
        tools.filter { isOnline || !it.requiresNetwork || it.isLocalFallback }

    /** Find a tool by its machine [name] (for function-call dispatch). */
    fun findByName(name: String): Tool? = tools.firstOrNull { it.name == name }

    /** Tools that expose a [ToolSchema] for LLM function calling. */
    fun availableSchemas(isOnline: Boolean): List<ToolSchema> =
        available(isOnline).mapNotNull { it.schema() }
}
