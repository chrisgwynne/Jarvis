package com.jarvis.assistant.core.routines

import android.util.Log
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.llm.NetworkClient
import com.jarvis.assistant.runtime.plan.Plan
import com.jarvis.assistant.runtime.plan.PlannedStep
import java.util.UUID

/**
 * RoutineRepository — save / list / delete / materialise [SavedRoutineEntity]
 * records. Converts to and from the [Plan] shape so [PlanRunner.execute]
 * can run a saved routine unchanged.
 *
 * [save] takes a user-facing name plus an ordered list of steps. Storage
 * uses a normalised lowercase/trimmed key so "Morning Coffee" and
 * "morning  coffee" resolve to the same row.
 */
class RoutineRepository(
    private val dao: SavedRoutineDao,
) {
    suspend fun save(name: String, steps: List<PlannedStep>): SavedRoutineEntity {
        val normalized = normalize(name)
        val stepsJson = NetworkClient.gson.toJson(steps.map(::toStored))
        val entity = SavedRoutineEntity(
            name = name.trim(),
            nameNormalized = normalized,
            stepsJson = stepsJson,
            createdAtMs = System.currentTimeMillis(),
        )
        val id = dao.upsert(entity)
        return entity.copy(id = id)
    }

    suspend fun list(): List<SavedRoutineEntity> = dao.listAll()

    suspend fun findByName(name: String): SavedRoutineEntity? =
        dao.findByName(normalize(name))

    suspend fun delete(name: String): Boolean =
        dao.deleteByName(normalize(name)) > 0

    suspend fun markRun(id: Long) = dao.markRun(id, System.currentTimeMillis())

    /**
     * Materialise a persisted routine into a runnable [Plan]. Caller
     * executes via PlanRunner.execute. The plan is marked as not needing
     * user re-confirmation — the initial save-as-routine flow already
     * captured consent for this sequence.
     */
    fun toPlan(entity: SavedRoutineEntity, originatingTranscript: String): Plan? {
        val steps = parseSteps(entity.stepsJson) ?: return null
        val allReversible = steps.all { it.reversible }
        return Plan(
            id = UUID.randomUUID().toString(),
            steps = steps,
            summarySpoken = "Running ${entity.name}.",
            allReversible = allReversible,
            originatingTranscript = originatingTranscript,
            autoRollbackOnHalt = allReversible,
        )
    }

    private fun parseSteps(json: String): List<PlannedStep>? = try {
        val type = object : TypeToken<List<StoredStep>>() {}.type
        val stored: List<StoredStep> = NetworkClient.gson.fromJson(json, type)
        stored.mapIndexed { idx, s ->
            PlannedStep(
                ordinal = idx,
                toolName = s.toolName,
                argsJson = s.argsJson,
                shortLabel = s.shortLabel,
                reversible = s.reversible,
            )
        }
    } catch (e: Exception) {
        Log.w("RoutineRepository", "parseSteps failed: ${e.message}")
        null
    }

    private fun toStored(step: PlannedStep): StoredStep = StoredStep(
        toolName = step.toolName,
        argsJson = step.argsJson,
        shortLabel = step.shortLabel,
        reversible = step.reversible,
    )

    private fun normalize(name: String): String = name.trim().lowercase().replace(WHITESPACE, " ")

    private data class StoredStep(
        val toolName: String,
        val argsJson: String,
        val shortLabel: String,
        val reversible: Boolean,
    )

    companion object { private val WHITESPACE = Regex("""\s+""") }
}
