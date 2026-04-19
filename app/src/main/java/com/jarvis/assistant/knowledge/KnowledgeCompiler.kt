package com.jarvis.assistant.knowledge

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.jarvis.assistant.knowledge.db.entity.ContradictionRecord
import com.jarvis.assistant.knowledge.db.entity.FactRecord
import com.jarvis.assistant.knowledge.db.entity.KnowledgeLogEntry
import com.jarvis.assistant.knowledge.db.entity.KnowledgeSource
import com.jarvis.assistant.knowledge.db.entity.WikiPage
import com.jarvis.assistant.llm.Message

/**
 * KnowledgeCompiler — the core ingest and compilation engine.
 *
 * FLOW
 * ────
 * 1. ingest()      — store raw source immediately (non-blocking to caller)
 * 2. compilePending() — process uncompiled sources in batches via LLM
 * 3. compileDailySummary() — roll up today's TRANSIENT sources into a DAILY_SUMMARY page
 *
 * All LLM calls are fire-and-forget from the voice pipeline perspective;
 * compilation runs on the IO dispatcher after the session closes.
 */
class KnowledgeCompiler(
    private val repo: KnowledgeRepository,
    private val resolver: EntityResolver,
    private val llm: suspend (List<Message>) -> String
) {

    companion object {
        private const val TAG = "KnowledgeCompiler"
        private val gson = Gson()

        private const val EXTRACT_SYSTEM = """Extract named entities and key facts from the text. Return ONLY minified JSON.
Format: {"e":[{"n":"Name","t":"PERSON|PLACE|PROJECT|TOPIC","f":[{"p":"predicate_snake_case","o":"value"}]}]}
Rules: max 4 entities, max 3 facts each, predicates are short snake_case strings.
If no clear entities exist return {"e":[]}.
Do not include markdown fences."""

        private const val SUMMARY_SYSTEM = """Update this knowledge entry with new facts. Return ONLY the updated summary (1-2 sentences, plain text, no markdown).
Keep it factual and concise."""
    }

    // ── Ingest ──────────────────────────────────────────────────────────────

    /**
     * Store a raw source immediately. Returns the new source ID.
     * Compilation is NOT done here — call compilePending() separately.
     */
    suspend fun ingest(
        rawText: String,
        sourceType: String = KnowledgeSource.VOICE_TRANSCRIPT,
        retentionClass: String = KnowledgeSource.TRANSIENT,
        metadataJson: String? = null
    ): Long {
        if (rawText.isBlank()) return -1L
        val source = KnowledgeSource(
            sourceType     = sourceType,
            rawText        = rawText.take(4000), // cap raw text
            retentionClass = retentionClass,
            metadataJson   = metadataJson
        )
        val id = repo.sources.insert(source)
        repo.log.insert(KnowledgeLogEntry(
            operationType = KnowledgeLogEntry.INGEST,
            summary       = "Ingested $sourceType source (${rawText.length} chars)"
        ))
        Log.d(TAG, "Ingested source id=$id type=$sourceType")
        return id
    }

    // ── Compile ─────────────────────────────────────────────────────────────

    /**
     * Process up to [batchSize] uncompiled sources.
     * Each source is extracted → entities resolved → pages updated → source marked compiled.
     */
    suspend fun compilePending(batchSize: Int = 5) {
        val uncompiled = repo.sources.getUncompiled(batchSize)
        if (uncompiled.isEmpty()) return

        Log.d(TAG, "Compiling ${uncompiled.size} pending sources")
        val affectedPageIds = mutableSetOf<Long>()

        for (source in uncompiled) {
            try {
                val entities = extractEntities(source.rawText)
                for (entity in entities) {
                    val page = resolver.resolveOrCreate(entity.name, entity.pageType)
                    val newFacts = processEntityFacts(page, entity.facts, source.id)
                    affectedPageIds.add(page.id)

                    // Refresh page from DB (it may have just been created)
                    val freshPage = repo.pages.getById(page.id) ?: page
                    updatePageSummary(freshPage, newFacts)

                    repo.pages.update(
                        freshPage.copy(
                            sourceCount = freshPage.sourceCount + 1,
                            updatedAt   = System.currentTimeMillis(),
                            confidence  = minOf(1.0f, freshPage.confidence + 0.05f)
                        )
                    )
                }
                repo.sources.markCompiled(source.id, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compile source ${source.id}: ${e.message}")
                // Mark as compiled anyway to avoid retry loops on bad input
                repo.sources.markCompiled(source.id, System.currentTimeMillis())
            }
        }

        if (affectedPageIds.isNotEmpty()) {
            repo.log.insert(KnowledgeLogEntry(
                operationType   = KnowledgeLogEntry.COMPILE,
                summary         = "Compiled ${uncompiled.size} sources → ${affectedPageIds.size} pages updated",
                affectedPageIds = affectedPageIds.joinToString(",")
            ))
        }
    }

    // ── Daily summary ────────────────────────────────────────────────────────

    /**
     * Roll up today's TRANSIENT sources into a DAILY_SUMMARY page.
     * [dateLabel] should be "YYYY-MM-DD".
     */
    suspend fun compileDailySummary(dateLabel: String) {
        val oneDayAgo    = System.currentTimeMillis() - 24L * 3_600_000
        val todaySources = repo.sources.getByRetentionClass(KnowledgeSource.TRANSIENT)
            .filter { it.createdAt >= oneDayAgo }
        if (todaySources.isEmpty()) {
            Log.d(TAG, "No TRANSIENT sources today — skipping daily summary")
            return
        }

        val combined = todaySources
            .joinToString("\n---\n") { it.rawText }
            .take(2000)

        val rawSummary = try {
            llm(listOf(
                Message("system", "Summarise these events from today in 2-3 sentences. Plain text only."),
                Message("user", combined)
            )).trim()
        } catch (e: Exception) {
            Log.w(TAG, "Daily summary LLM call failed: ${e.message}")
            return
        }

        // The LLM router returns error-shaped strings on provider failure rather
        // than throwing — don't persist those as the day's saved summary.
        if (rawSummary.isBlank() ||
            rawSummary == "Something went wrong." ||
            rawSummary.startsWith("Error:") ||
            rawSummary.startsWith("No API key") ||
            rawSummary.startsWith("HTTP ") ||
            rawSummary.startsWith("Network error:")
        ) {
            Log.w(TAG, "Daily summary came back error-shaped — skipping persist")
            return
        }
        val summaryText = rawSummary

        val normalized = "daily_summary_$dateLabel"
        val existing   = repo.pages.getByNormalizedTitle(normalized)
        val now        = System.currentTimeMillis()

        if (existing != null) {
            repo.pages.update(existing.copy(summary = summaryText, updatedAt = now))
        } else {
            repo.pages.insert(WikiPage(
                pageType        = WikiPage.DAILY_SUMMARY,
                title           = "Summary: $dateLabel",
                titleNormalized = normalized,
                summary         = summaryText,
                updatedAt       = now,
                confidence      = 0.8f,
                sourceCount     = todaySources.size
            ))
        }

        repo.log.insert(KnowledgeLogEntry(
            operationType = KnowledgeLogEntry.COMPACT,
            summary       = "Daily summary compiled for $dateLabel (${todaySources.size} sources)"
        ))
        Log.d(TAG, "Daily summary compiled for $dateLabel")
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private data class ExtractedFact(val predicate: String, val objectValue: String)
    private data class ExtractedEntity(val name: String, val pageType: String, val facts: List<ExtractedFact>)

    private suspend fun extractEntities(text: String): List<ExtractedEntity> {
        val truncated = text.take(600)
        val raw = try {
            llm(listOf(
                Message("system", EXTRACT_SYSTEM),
                Message("user", truncated)
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Entity extraction LLM call failed: ${e.message}")
            return emptyList()
        }

        return try {
            // Strip any accidental markdown fences
            val json = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val root  = gson.fromJson(json, JsonObject::class.java)
            val eArr  = root.getAsJsonArray("e") ?: return emptyList()
            eArr.mapNotNull { el ->
                val obj   = el.asJsonObject
                val name  = obj.get("n")?.asString?.trim() ?: return@mapNotNull null
                val type  = obj.get("t")?.asString?.trim() ?: WikiPage.TOPIC
                val fArr  = obj.getAsJsonArray("f")
                val facts = fArr?.mapNotNull { fe ->
                    val fo = fe.asJsonObject
                    val p  = fo.get("p")?.asString?.trim() ?: return@mapNotNull null
                    val o  = fo.get("o")?.asString?.trim() ?: return@mapNotNull null
                    ExtractedFact(p, o)
                } ?: emptyList()
                if (name.isBlank()) null else ExtractedEntity(name, type, facts)
            }
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "Failed to parse entity extraction JSON: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Entity extraction parse error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Insert new facts for the page, superseding any conflicting existing facts.
     * Returns list of newly inserted FactRecords.
     */
    private suspend fun processEntityFacts(
        page: WikiPage,
        extractedFacts: List<ExtractedFact>,
        sourceId: Long
    ): List<FactRecord> {
        val newFacts = mutableListOf<FactRecord>()
        for (ef in extractedFacts) {
            val existing = repo.facts.getByPageAndPredicate(page.id, ef.predicate)
            if (existing != null) {
                if (existing.objectValue.equals(ef.objectValue, ignoreCase = true)) {
                    continue // same fact, skip
                }
                // Contradiction: new value differs from old
                val newFact = FactRecord(
                    pageId      = page.id,
                    subject     = page.title,
                    predicate   = ef.predicate,
                    objectValue = ef.objectValue,
                    sourceId    = sourceId
                )
                val newId = repo.facts.insert(newFact)
                repo.facts.supersede(existing.id, newId)
                repo.contradictions.insert(ContradictionRecord(
                    pageId    = page.id,
                    oldFactId = existing.id,
                    newFactId = newId
                ))
                newFacts.add(newFact.copy(id = newId))
                Log.d(TAG, "Contradiction on ${page.title}.${ef.predicate}: '${existing.objectValue}' → '${ef.objectValue}'")
            } else {
                val fact = FactRecord(
                    pageId      = page.id,
                    subject     = page.title,
                    predicate   = ef.predicate,
                    objectValue = ef.objectValue,
                    sourceId    = sourceId
                )
                val newId = repo.facts.insert(fact)
                newFacts.add(fact.copy(id = newId))
            }
        }
        return newFacts
    }

    private suspend fun updatePageSummary(page: WikiPage, newFacts: List<FactRecord>) {
        // Skip summary update if no new facts and summary already exists
        if (newFacts.isEmpty() && page.summary.isNotBlank()) return
        // Don't call LLM for first-ingest stubs with no facts
        if (newFacts.isEmpty() && page.sourceCount == 0) return

        val factsText = newFacts.joinToString("\n") { "- ${it.predicate}: ${it.objectValue}" }
        val prompt = buildString {
            append("Entry: ${page.title} (${page.pageType})\n")
            if (page.summary.isNotBlank()) append("Current summary: ${page.summary}\n")
            if (factsText.isNotBlank()) append("New facts:\n$factsText")
        }

        val updated = try {
            llm(listOf(
                Message("system", SUMMARY_SYSTEM),
                Message("user", prompt)
            )).trim()
        } catch (e: Exception) {
            Log.w(TAG, "Summary update failed for page ${page.id}: ${e.message}")
            return
        }

        if (updated.isNotBlank()) {
            repo.pages.update(page.copy(summary = updated, updatedAt = System.currentTimeMillis()))
        }
    }
}
