package com.jarvis.assistant.knowledge

import com.jarvis.assistant.knowledge.db.dao.ContradictionDao
import com.jarvis.assistant.knowledge.db.dao.FactRecordDao
import com.jarvis.assistant.knowledge.db.dao.KnowledgeLogDao
import com.jarvis.assistant.knowledge.db.dao.KnowledgeSourceDao
import com.jarvis.assistant.knowledge.db.dao.PageLinkDao
import com.jarvis.assistant.knowledge.db.dao.WikiPageDao

/**
 * Unified access point for all knowledge DAOs.
 * No business logic here — logic lives in Compiler, QueryEngine, LintEngine.
 */
class KnowledgeRepository(
    val sources: KnowledgeSourceDao,
    val pages: WikiPageDao,
    val facts: FactRecordDao,
    val links: PageLinkDao,
    val log: KnowledgeLogDao,
    val contradictions: ContradictionDao
)
