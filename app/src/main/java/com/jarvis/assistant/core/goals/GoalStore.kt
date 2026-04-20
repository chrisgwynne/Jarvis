package com.jarvis.assistant.core.goals

import com.jarvis.assistant.core.situations.SituationType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GoalStore — thin lifecycle manager over [GoalDao].
 *
 * Owns:
 *   - idempotent upsert keyed on [Goal.rootDedupeKey],
 *   - transitions to COMPLETED / ABANDONED / FAILED,
 *   - TTL sweeping (overdue active goals become ABANDONED),
 *   - an observable [active] snapshot for prompt and decision code.
 *
 * Evidence is encoded as a tiny newline-joined string to avoid dragging in
 * a JSON codec for three-element lists. The encoding is internal; callers
 * see and pass `List<String>`.
 */
class GoalStore(
    private val dao: GoalDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private val _active = MutableStateFlow<List<Goal>>(emptyList())
    val active: StateFlow<List<Goal>> = _active.asStateFlow()

    /** Hydrate the in-memory active snapshot from disk. Safe to call on startup. */
    suspend fun hydrate() {
        val rows = dao.activeAt(nowMs())
        mutex.withLock {
            _active.value = rows.map { it.toGoal() }
        }
    }

    /** Insert-or-update a goal keyed by [Goal.rootDedupeKey]. Returns the
     *  (possibly merged) goal with a populated id. */
    suspend fun upsert(
        type: GoalType,
        title: String,
        rootDedupeKey: String,
        ttlMs: Long,
        originSituation: SituationType? = null,
        evidence: List<String> = emptyList(),
    ): Goal {
        val now = nowMs()
        val existing = dao.findByRootKey(rootDedupeKey)
        val result = if (existing == null) {
            val entity = GoalEntity(
                type = type.name,
                title = title,
                status = Goal.STATUS_ACTIVE,
                originSituation = originSituation?.name,
                rootDedupeKey = rootDedupeKey,
                createdAtMs = now,
                updatedAtMs = now,
                expiresAtMs = now + ttlMs,
                evidenceJson = encodeEvidence(evidence),
            )
            val id = dao.insert(entity)
            entity.copy(id = id).toGoal()
        } else {
            val merged = existing.copy(
                type = type.name,
                title = title,
                status = Goal.STATUS_ACTIVE,
                updatedAtMs = now,
                expiresAtMs = now + ttlMs,
                evidenceJson = encodeEvidence(evidence),
                originSituation = originSituation?.name ?: existing.originSituation,
            )
            dao.update(merged)
            merged.toGoal()
        }
        refreshSnapshot()
        return result
    }

    suspend fun complete(id: Long) {
        dao.setStatus(id, Goal.STATUS_COMPLETED, nowMs())
        refreshSnapshot()
    }

    suspend fun abandon(id: Long) {
        dao.setStatus(id, Goal.STATUS_ABANDONED, nowMs())
        refreshSnapshot()
    }

    suspend fun fail(id: Long) {
        dao.setStatus(id, Goal.STATUS_FAILED, nowMs())
        refreshSnapshot()
    }

    /** Move every overdue ACTIVE goal to ABANDONED. Call opportunistically
     *  (e.g. from the proactive tick). */
    suspend fun sweep(): Int {
        val rows = dao.overdue(nowMs())
        for (row in rows) dao.setStatus(row.id, Goal.STATUS_ABANDONED, nowMs())
        if (rows.isNotEmpty()) refreshSnapshot()
        return rows.size
    }

    /** Convenience read used by prompts/traces. Null when no goal is open. */
    fun topActive(): Goal? = _active.value.firstOrNull()

    suspend fun findActiveOfType(type: GoalType): Goal? =
        _active.value.firstOrNull { it.type == type }

    private suspend fun refreshSnapshot() {
        val rows = dao.activeAt(nowMs())
        mutex.withLock {
            _active.value = rows.map { it.toGoal() }
        }
    }

    companion object {
        private const val SEP = "\u0001"

        internal fun encodeEvidence(list: List<String>): String =
            list.joinToString(SEP) { it.replace(SEP, " ") }

        internal fun decodeEvidence(raw: String): List<String> =
            if (raw.isEmpty()) emptyList() else raw.split(SEP)
    }
}

private fun GoalEntity.toGoal(): Goal = Goal(
    id = id,
    type = runCatching { GoalType.valueOf(type) }.getOrDefault(GoalType.AD_HOC),
    title = title,
    status = status,
    originSituation = originSituation?.let {
        runCatching { SituationType.valueOf(it) }.getOrNull()
    },
    rootDedupeKey = rootDedupeKey,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
    expiresAtMs = expiresAtMs,
    evidence = GoalStore.decodeEvidence(evidenceJson),
)
