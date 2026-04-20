package com.jarvis.assistant.core.situations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SituationRegistry — thread-safe, in-memory store of the currently-active
 * [Situation]s.
 *
 * The registry is a pure runtime artefact: no Room table, no disk, no
 * process survival. Situations come and go with context, and the evaluator
 * recomputes them every proactive tick, so persistence would only add lag
 * and stale reads.
 *
 * Callers observe the registry via [snapshot], a [StateFlow] that emits a
 * list sorted by [Situation.weight] descending. Reads are non-blocking;
 * mutations go through an internal mutex so concurrent evaluator ticks can
 * never corrupt the set.
 */
class SituationRegistry {

    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow<List<Situation>>(emptyList())

    /** Observable view of the currently-active situations, ordered by
     *  weight (most urgent × confident first). */
    val snapshot: StateFlow<List<Situation>> = _snapshot.asStateFlow()

    /** Replace the active set with [incoming], dropping anything expired
     *  against [nowMs]. Typically called once per evaluator tick.
     *
     *  If [incoming] contains multiple entries for the same [SituationType]
     *  only the highest-weighted one survives — situations are classes of
     *  context, not individual events.
     */
    suspend fun update(incoming: List<Situation>, nowMs: Long) {
        val fresh = incoming
            .filterNot { it.isExpired(nowMs) }
            .groupBy { it.type }
            .mapValues { (_, list) -> list.maxByOrNull { it.weight }!! }
            .values
            .sortedByDescending { it.weight }

        mutex.withLock {
            _snapshot.value = fresh
        }
    }

    /** Evict any expired entries without replacing the set. Safe to call on
     *  a timer or before reads when no new evaluation has occurred. */
    suspend fun sweep(nowMs: Long) {
        mutex.withLock {
            val current = _snapshot.value
            val kept = current.filterNot { it.isExpired(nowMs) }
            if (kept.size != current.size) _snapshot.value = kept
        }
    }

    /** The top situation right now (or null). Convenient for prompts and
     *  decision traces that only care about the most important one. */
    fun top(): Situation? = _snapshot.value.firstOrNull()

    /** Is a situation of [type] currently active? */
    fun has(type: SituationType): Boolean =
        _snapshot.value.any { it.type == type }
}
