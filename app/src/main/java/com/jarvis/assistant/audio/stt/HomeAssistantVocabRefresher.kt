package com.jarvis.assistant.audio.stt

import android.util.Log
import com.jarvis.assistant.tools.smart.HomeAssistantClient

/**
 * HomeAssistantVocabRefresher — pulls the current HA entity list and feeds
 * derived room + device vocab into [VocabularyBiaser].
 *
 * Run at startup (and on demand whenever the HA state cache invalidates).
 * Cheap when HA isn't configured: [HomeAssistantClient.getStates] returns
 * an empty list and we early-out.
 *
 * Naming heuristics (cheap, no LLM):
 *  - Entity ID `light.kitchen`            → room "kitchen", device "lights"
 *  - Entity ID `fan.living_room`          → room "living room", device "fan"
 *  - Entity ID `lock.front_door_2`        → device "front door"
 *  - Entity ID `camera.front_door`        → device "front door camera"
 *  - Friendly name added verbatim as a runtime vocab token
 *
 * The biaser is the single source of truth — adding here means the
 * [TranscriptCorrector] and [SpeechCandidateScorer] downstream see it
 * without any other wiring.
 */
object HomeAssistantVocabRefresher {

    private const val TAG = "HaVocabRefresher"

    private val ROOM_DOMAINS   = setOf("light", "fan", "switch", "media_player", "climate", "cover")
    private val DEVICE_DOMAINS = setOf("lock", "camera", "binary_sensor", "sensor", "alarm_control_panel")

    suspend fun refresh(client: HomeAssistantClient?) {
        if (client == null) {
            Log.d(TAG, "[HA_VOCAB_REFRESH_SKIPPED] no HA client configured")
            return
        }
        Log.d(TAG, "[HA_VOCAB_REFRESH_START]")
        val entities = try {
            client.getStates()
        } catch (e: Exception) {
            Log.w(TAG, "[HA_VOCAB_REFRESH_FAILED] ${e.message}")
            emptyList()
        }
        if (entities.isEmpty()) {
            Log.d(TAG, "[HA_VOCAB_REFRESH_DONE] entities=0")
            return
        }

        val rooms   = linkedSetOf<String>()
        val devices = linkedSetOf<String>()

        for (e in entities) {
            val domain    = e.domain
            val name      = e.friendlyName.ifBlank { e.entityId.substringAfter('.') }
            val tail      = e.entityId.substringAfter('.', missingDelimiterValue = "")
            val roomPart  = humanise(tail).trim()

            // Always seed the friendly name as a vocab token — covers
            // anything our heuristics miss (scenes, automations, custom).
            if (name.isNotBlank()) {
                when (domain) {
                    in ROOM_DOMAINS   -> rooms.add(name)
                    in DEVICE_DOMAINS -> devices.add(name)
                    else              -> rooms.add(name)   // benign default
                }
                Log.v(TAG, "[HA_VOCAB_ENTITY_ALIAS_ADDED] entity=${e.entityId} alias=\"$name\"")
            }

            if (roomPart.isNotBlank() && domain in ROOM_DOMAINS) {
                rooms.add(roomPart)
            }
            if (roomPart.isNotBlank() && domain in DEVICE_DOMAINS) {
                devices.add(roomPart)
            }

            // Synthesise a "<room> <device-type>" alias so "kitchen lights"
            // / "living room fan" become first-class vocab tokens.
            val deviceClass = when (domain) {
                "light"        -> "lights"
                "fan"          -> "fan"
                "switch"       -> null      // ambiguous on its own
                "media_player" -> null
                "climate"      -> "heating"
                "cover"        -> "blinds"
                "lock"         -> null
                "camera"       -> "camera"
                else           -> null
            }
            if (deviceClass != null && roomPart.isNotBlank()) {
                val composite = "$roomPart $deviceClass".trim()
                devices.add(composite)
                Log.v(TAG, "[HA_VOCAB_ENTITY_ALIAS_ADDED] composite=\"$composite\"")
            }
        }

        VocabularyBiaser.setRuntimeVocab(
            rooms   = rooms,
            devices = devices
        )
        Log.d(TAG, "[HA_VOCAB_REFRESH_DONE] rooms=${rooms.size} devices=${devices.size}")
    }

    /** Convert `front_door_2` → `front door`.  Strips trailing digits. */
    private fun humanise(token: String): String =
        token.replace('_', ' ')
            .replace(Regex("\\s+\\d+$"), "")
            .trim()
}
