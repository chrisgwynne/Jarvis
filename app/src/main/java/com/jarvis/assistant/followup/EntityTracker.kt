package com.jarvis.assistant.followup

import android.util.Log

/**
 * EntityTracker — maintains a short-term list of entities (contacts, apps,
 * locations, etc.) that were mentioned during the current conversation session.
 *
 * Used for pronoun and reference resolution across turns:
 *   "Message Chris" → later "tell him I'm late" → resolves "him" → Chris
 *
 * Salience starts at 1.0 for every new entity and decays by [SALIENCE_DECAY]
 * after each turn.  Entities at zero salience are pruned.
 *
 * NOT thread-safe by design — all access must be on the Main dispatcher,
 * consistent with the rest of the audio pipeline.
 */
class EntityTracker {

    companion object {
        private const val TAG          = "EntityTracker"
        private const val MAX_ENTITIES = 24
        private const val SALIENCE_DECAY = 0.15f
    }

    private val entities: MutableList<EntityReference> = mutableListOf()

    // ── Mutation ───────────────────────────────────────────────────────────────

    /**
     * Add or refresh an entity.  If one with the same type + label (case-insensitive)
     * already exists its salience is reset to 1.0.
     */
    fun track(entity: EntityReference) {
        val idx = entities.indexOfFirst {
            it.entityType == entity.entityType &&
            it.label.equals(entity.label, ignoreCase = true)
        }
        if (idx >= 0) {
            entities[idx] = entities[idx].copy(
                salience   = 1.0f,
                lastSeenAt = System.currentTimeMillis()
            )
        } else {
            entities.add(entity.copy(salience = 1.0f))
            if (entities.size > MAX_ENTITIES) entities.removeAt(0)
        }
        Log.d(TAG, "Tracked ${entity.entityType} '${entity.label}'")
    }

    /**
     * Reduce salience of all entities by [SALIENCE_DECAY].
     * Call this once per conversation turn.
     */
    fun decaySalience() {
        entities.replaceAll { it.copy(salience = (it.salience - SALIENCE_DECAY).coerceAtLeast(0f)) }
        entities.removeAll { it.salience <= 0f }
    }

    // ── Query ──────────────────────────────────────────────────────────────────

    fun getRecent(type: EntityType, limit: Int = 3): List<EntityReference> =
        entities
            .filter { it.entityType == type }
            .sortedByDescending { it.lastSeenAt }
            .take(limit)

    fun getMostSalient(type: EntityType): EntityReference? =
        entities
            .filter { it.entityType == type }
            .maxByOrNull { it.salience * it.confidence }

    /**
     * Resolve a personal pronoun to the most salient entity of the relevant type.
     *
     * Gender disambiguation is best-effort: for "him/her" we just return the
     * most salient CONTACT since we don't do gender detection.
     */
    fun resolvePronoun(pronoun: String): EntityReference? {
        return when (pronoun.lowercase().trim()) {
            "him", "his", "he",
            "her", "she",
            "them", "they", "their" -> getMostSalient(EntityType.CONTACT)
            "it", "that"            ->
                entities.filter { it.entityType != EntityType.CONTACT }
                        .maxByOrNull { it.salience }
                    ?: getMostSalient(EntityType.CONTACT)
            "there"                 -> getMostSalient(EntityType.LOCATION)
            else                    -> null
        }
    }

    /** Clear all tracked entities — call when a new activation session starts. */
    fun clear() {
        entities.clear()
        Log.d(TAG, "Entity tracker cleared")
    }

    /** Extract and track entities from a raw utterance (best-effort heuristic). */
    fun updateFromUtterance(text: String) {
        extractEntities(text).forEach { track(it) }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private val CONTACT_PREFIXES = Regex(
        """(?:message|text|call|ring|phone|dial|whatsapp|contact|send\s+(?:a\s+)?(?:message|text)\s+to)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)""",
        RegexOption.IGNORE_CASE
    )

    private fun extractEntities(text: String): List<EntityReference> {
        val result = mutableListOf<EntityReference>()

        CONTACT_PREFIXES.find(text)?.let { m ->
            result.add(
                EntityReference(
                    entityType = EntityType.CONTACT,
                    label      = m.groupValues[1].trim(),
                    confidence = 0.85f
                )
            )
        }

        // "tomorrow" / "today" → TIME_REFERENCE
        if (text.contains("tomorrow", ignoreCase = true)) {
            result.add(EntityReference(EntityType.TIME_REFERENCE, label = "tomorrow"))
        }

        return result
    }
}
