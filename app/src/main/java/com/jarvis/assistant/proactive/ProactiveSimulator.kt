package com.jarvis.assistant.proactive

/**
 * ProactiveSimulator — synthesise a [ContextSnapshot] and see what action
 * the engine would produce, without running the polling loop.
 *
 * Intended for manual tuning of cooldowns, presence gates and suppression
 * thresholds from a debug screen or unit test — feed a snapshot in, read
 * the decision out, no side effects on the real engine.
 *
 * Example:
 *   val sim = ProactiveSimulator(config)
 *   val snapshot = ContextSnapshot(..., batteryLevel = 4, isCharging = false, ...)
 *   val result = sim.simulate(snapshot)
 *   // result.events   — what EventGenerator produced
 *   // result.scored   — what EventScorer ranked
 *   // result.action   — the ProactiveAction DecisionEngine chose
 */
class ProactiveSimulator(private val config: ProactiveConfig) {

    data class SimulationResult(
        val events: List<ProactiveEvent>,
        val scored: List<EventScorer.ScoredEvent>,
        val action: ProactiveAction,
        val metrics: Map<ProactiveMetrics.Counter, Long>
    )

    private val generator = EventGenerator(config)

    /**
     * Run generate → score → decide against [snapshot].
     *
     * A fresh [CooldownStore] is used so the real store's state is never
     * mutated. If you want to simulate with a primed history, call
     * simulateWith(snapshot, primed) instead.
     */
    fun simulate(snapshot: ContextSnapshot): SimulationResult =
        simulateWith(snapshot, CooldownStore())

    fun simulateWith(
        snapshot: ContextSnapshot,
        cooldownStore: CooldownStore
    ): SimulationResult {
        val before = ProactiveMetrics.snapshot()
        val events = generator.generate(snapshot)
        val scorer = EventScorer(config, cooldownStore)
        val decision = DecisionEngine(config, cooldownStore)
        val scored = scorer.scoreAll(events, snapshot)
        val action = decision.decide(scored, snapshot)
        val after = ProactiveMetrics.snapshot()
        val diff = after.mapValues { (k, v) -> v - (before[k] ?: 0L) }
            .filterValues { it != 0L }
        return SimulationResult(events, scored, action, diff)
    }
}
