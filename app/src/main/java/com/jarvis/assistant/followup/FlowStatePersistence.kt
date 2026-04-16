package com.jarvis.assistant.followup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * FlowStatePersistence — saves and restores the current [ActiveFlow] across
 * JarvisService restarts using plain SharedPreferences.
 *
 * Flow data contains only operational context (slot values, timings) — nothing
 * sensitive — so plain SharedPreferences is sufficient.
 *
 * THREADING: Must be accessed on the Main dispatcher, consistent with the rest
 * of the follow-up pipeline.
 */
class FlowStatePersistence(context: Context) {

    companion object {
        private const val TAG        = "FlowStatePersistence"
        private const val PREFS_NAME = "jarvis_flow_state"
        private const val KEY_FLOW   = "active_flow"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson: Gson = GsonBuilder()
        .registerTypeHierarchyAdapter(ArrayDeque::class.java, ArrayDequeAdapter())
        .create()

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Persist [flow] to SharedPreferences.
     * Passing null clears any previously saved flow.
     */
    fun saveFlow(flow: ActiveFlow?) {
        if (flow == null) {
            prefs.edit().remove(KEY_FLOW).apply()
            Log.d(TAG, "Cleared persisted flow")
            return
        }
        try {
            val dto  = ActiveFlowDto.from(flow)
            val json = gson.toJson(dto)
            prefs.edit().putString(KEY_FLOW, json).apply()
            Log.d(TAG, "Saved ${flow.type} flow (expires ${flow.expiresAt})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save flow: ${e.message}", e)
        }
    }

    /**
     * Restore a previously saved [ActiveFlow].
     *
     * Returns null if:
     * - Nothing was saved, or
     * - The saved data can't be parsed, or
     * - The saved flow has already expired.
     */
    fun restoreFlow(): ActiveFlow? {
        val json = prefs.getString(KEY_FLOW, null) ?: return null
        return try {
            val dto  = gson.fromJson(json, ActiveFlowDto::class.java)
            val flow = dto.toActiveFlow()
            if (flow.isExpired()) {
                Log.d(TAG, "Discarding expired persisted flow (${flow.type})")
                prefs.edit().remove(KEY_FLOW).apply()
                null
            } else {
                flow
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore flow: ${e.message}", e)
            prefs.edit().remove(KEY_FLOW).apply()
            null
        }
    }

    // ── DTO ────────────────────────────────────────────────────────────────────

    /**
     * Plain-object representation of [ActiveFlow] safe for Gson serialisation.
     *
     * [ArrayDeque] fields are flattened to [List] so Gson can round-trip them
     * without needing a custom type adapter for each field individually.
     */
    private data class ActiveFlowDto(
        val id: String,
        val type: String,
        val status: String,
        val createdAt: Long,
        val updatedAt: Long,
        val expiresAt: Long,
        val sourceConversationId: String?,
        val expectedSlot: String?,
        /** Slot values: key name → raw string value + confidence. */
        val collectedSlots: Map<String, SlotValueDto>,
        /** Ordered list of still-missing slot key names (was ArrayDeque). */
        val missingSlots: List<String>,
        val lastPrompt: String?,
        val confidenceScore: Float,
        val turnCount: Int,
        val completionSummary: String?
    ) {
        companion object {
            fun from(flow: ActiveFlow) = ActiveFlowDto(
                id                   = flow.id,
                type                 = flow.type.name,
                status               = flow.status.name,
                createdAt            = flow.createdAt,
                updatedAt            = flow.updatedAt,
                expiresAt            = flow.expiresAt,
                sourceConversationId = flow.sourceConversationId,
                expectedSlot         = flow.expectedSlot?.name,
                collectedSlots       = flow.collectedSlots.entries.associate { (k, v) ->
                    k.name to SlotValueDto(v.raw, v.confidence)
                },
                missingSlots         = flow.missingSlots.map { it.name },
                lastPrompt           = flow.lastPrompt,
                confidenceScore      = flow.confidenceScore,
                turnCount            = flow.turnCount,
                completionSummary    = flow.completionSummary
            )
        }

        fun toActiveFlow(): ActiveFlow {
            val collectedMap: MutableMap<SlotKey, SlotValue> = collectedSlots.entries
                .mapNotNull { (k, v) ->
                    runCatching { SlotKey.valueOf(k) to SlotValue(raw = v.raw, confidence = v.confidence) }.getOrNull()
                }
                .toMap()
                .toMutableMap()

            val missingDeque: ArrayDeque<SlotKey> = ArrayDeque(
                missingSlots.mapNotNull { runCatching { SlotKey.valueOf(it) }.getOrNull() }
            )

            return ActiveFlow(
                id                   = id,
                type                 = FlowType.valueOf(type),
                status               = FlowStatus.valueOf(status),
                createdAt            = createdAt,
                updatedAt            = updatedAt,
                expiresAt            = expiresAt,
                sourceConversationId = sourceConversationId,
                expectedSlot         = expectedSlot?.let { runCatching { SlotKey.valueOf(it) }.getOrNull() },
                collectedSlots       = collectedMap,
                missingSlots         = missingDeque,
                lastPrompt           = lastPrompt,
                confidenceScore      = confidenceScore,
                turnCount            = turnCount,
                completionSummary    = completionSummary
            )
        }
    }

    private data class SlotValueDto(val raw: String, val confidence: Float)

    // ── ArrayDeque Gson adapter (safety net) ──────────────────────────────────

    /**
     * Teaches Gson to serialise/deserialise ArrayDeque as a plain JSON array.
     * This is a fallback in case Gson encounters an ArrayDeque anywhere that
     * isn't already handled by the DTO layer.
     */
    private class ArrayDequeAdapter : JsonSerializer<ArrayDeque<*>>, JsonDeserializer<ArrayDeque<*>> {
        private val listType = object : TypeToken<List<Any>>() {}.type

        override fun serialize(
            src: ArrayDeque<*>,
            typeOfSrc: Type,
            context: JsonSerializationContext
        ): JsonElement = context.serialize(src.toList())

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): ArrayDeque<*> {
            val list = context.deserialize<List<Any>>(json, listType)
            return ArrayDeque(list)
        }
    }
}
