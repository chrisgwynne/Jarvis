package com.jarvis.assistant.proactive

import java.util.concurrent.atomic.AtomicLong

/**
 * ProactiveMetrics — in-memory counters describing what the engine did and
 * why it chose (or chose not to) surface something.
 *
 * Lightweight by design: no persistence, no flushing.  A debug UI or a
 * unit test can snapshot() the counters at any time to decide whether the
 * engine is well-tuned (too many suppressions, too many ignored verdicts,
 * etc.).
 */
object ProactiveMetrics {

    enum class Counter {
        EVENTS_GENERATED,
        ACTIONS_DISPATCHED,
        SUPPRESSED_EMPTY,
        SUPPRESSED_GLOBAL_GAP,
        SUPPRESSED_QUIET_HOURS,
        SUPPRESSED_PRESENCE,
        VERDICT_ACCEPTED,
        VERDICT_IGNORED,
        VERDICT_DISPLACED
    }

    private val values: Map<Counter, AtomicLong> =
        Counter.values().associateWith { AtomicLong(0) }

    fun increment(c: Counter, by: Long = 1L) {
        values.getValue(c).addAndGet(by)
    }

    fun snapshot(): Map<Counter, Long> =
        values.mapValues { (_, v) -> v.get() }

    /** Reset all counters — intended for tests. */
    fun reset() = values.values.forEach { it.set(0) }
}
