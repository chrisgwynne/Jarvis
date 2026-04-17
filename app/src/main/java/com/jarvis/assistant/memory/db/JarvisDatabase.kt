package com.jarvis.assistant.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jarvis.assistant.brain.db.dao.BrainEventDao
import com.jarvis.assistant.brain.db.dao.BrainPatternDao
import com.jarvis.assistant.brain.db.entity.BrainEvent
import com.jarvis.assistant.brain.db.entity.BrainPattern
import com.jarvis.assistant.proactive.followup.PendingFollowUp
import com.jarvis.assistant.proactive.followup.PendingFollowUpDao
import com.jarvis.assistant.knowledge.db.dao.ContradictionDao
import com.jarvis.assistant.knowledge.db.dao.FactRecordDao
import com.jarvis.assistant.knowledge.db.dao.KnowledgeLogDao
import com.jarvis.assistant.knowledge.db.dao.KnowledgeSourceDao
import com.jarvis.assistant.knowledge.db.dao.PageLinkDao
import com.jarvis.assistant.knowledge.db.dao.WikiPageDao
import com.jarvis.assistant.knowledge.db.entity.ContradictionRecord
import com.jarvis.assistant.knowledge.db.entity.FactRecord
import com.jarvis.assistant.knowledge.db.entity.KnowledgeLogEntry
import com.jarvis.assistant.knowledge.db.entity.KnowledgeSource
import com.jarvis.assistant.knowledge.db.entity.PageLink
import com.jarvis.assistant.knowledge.db.entity.WikiPage
import com.jarvis.assistant.memory.db.dao.ConversationDao
import com.jarvis.assistant.memory.db.dao.MemoryDao
import com.jarvis.assistant.memory.db.dao.MemoryFactDao
import com.jarvis.assistant.memory.db.entity.ConversationSession
import com.jarvis.assistant.memory.db.entity.ConversationTurn
import com.jarvis.assistant.memory.db.entity.MemoryEntry
import com.jarvis.assistant.memory.db.entity.MemoryFact
import com.jarvis.assistant.reminders.db.dao.ScheduledItemDao
import com.jarvis.assistant.reminders.db.entity.ScheduledItem
import com.jarvis.assistant.shopping.ShoppingDao
import com.jarvis.assistant.shopping.ShoppingItem
import com.jarvis.assistant.speaker.db.PersonRecord
import com.jarvis.assistant.speaker.db.PersonRecordDao
import com.jarvis.assistant.speaker.db.RecentGuest
import com.jarvis.assistant.speaker.db.RecentGuestDao
import com.jarvis.assistant.speaker.db.SpeakerEmbedding
import com.jarvis.assistant.speaker.db.SpeakerEmbeddingDao

@Database(
    entities = [
        MemoryEntry::class,
        ConversationSession::class,
        ConversationTurn::class,
        MemoryFact::class,
        ScheduledItem::class,
        ShoppingItem::class,
        PersonRecord::class,
        SpeakerEmbedding::class,
        RecentGuest::class,
        KnowledgeSource::class,
        WikiPage::class,
        FactRecord::class,
        PageLink::class,
        KnowledgeLogEntry::class,
        ContradictionRecord::class,
        PendingFollowUp::class,
        BrainEvent::class,
        BrainPattern::class
    ],
    version = 8,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryFactDao(): MemoryFactDao
    abstract fun scheduledItemDao(): ScheduledItemDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun personRecordDao(): PersonRecordDao
    abstract fun speakerEmbeddingDao(): SpeakerEmbeddingDao
    abstract fun recentGuestDao(): RecentGuestDao
    abstract fun knowledgeSourceDao(): KnowledgeSourceDao
    abstract fun wikiPageDao(): WikiPageDao
    abstract fun factRecordDao(): FactRecordDao
    abstract fun pageLinkDao(): PageLinkDao
    abstract fun knowledgeLogDao(): KnowledgeLogDao
    abstract fun contradictionDao(): ContradictionDao
    abstract fun pendingFollowUpDao(): PendingFollowUpDao
    abstract fun brainEventDao(): BrainEventDao
    abstract fun brainPatternDao(): BrainPatternDao

    companion object {
        private const val DB_NAME = "jarvis.db"

        @Volatile
        private var INSTANCE: JarvisDatabase? = null

        /**
         * Migration 1 → 2: add memory_facts and scheduled_items tables.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memory_facts (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        factKey       TEXT    NOT NULL,
                        value         TEXT    NOT NULL,
                        category      TEXT    NOT NULL,
                        lastUpdatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_memory_facts_factKey ON memory_facts(factKey)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_memory_facts_category ON memory_facts(category)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_items (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        label            TEXT    NOT NULL,
                        triggerAtMs      INTEGER NOT NULL,
                        type             TEXT    NOT NULL,
                        status           TEXT    NOT NULL DEFAULT 'PENDING',
                        deliveryMode     TEXT    NOT NULL DEFAULT 'SPEAK_IF_IDLE',
                        repeatIntervalMs INTEGER NOT NULL DEFAULT 0,
                        createdAt        INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scheduled_items_status ON scheduled_items(status)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scheduled_items_triggerAtMs ON scheduled_items(triggerAtMs)"
                )
            }
        }

        /**
         * Migration 2 → 3: add shopping_items table.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS shopping_items (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        item      TEXT    NOT NULL,
                        addedAt   INTEGER NOT NULL,
                        completed INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration 3 → 4: add person_records and speaker_embeddings tables for
         * speaker recognition and identity management.
         *
         * All existing data is preserved — no destructive changes.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS person_records (
                        id                     INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName            TEXT    NOT NULL,
                        enrollmentStatus       TEXT    NOT NULL DEFAULT 'NONE',
                        enrolledUtteranceCount INTEGER NOT NULL DEFAULT 0,
                        lastSeenAt             INTEGER NOT NULL,
                        greetByName            INTEGER NOT NULL DEFAULT 1,
                        isOwner                INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_person_records_displayName ON person_records(displayName)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS speaker_embeddings (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        personId      INTEGER NOT NULL,
                        embeddingBlob BLOB    NOT NULL,
                        capturedAt    INTEGER NOT NULL,
                        FOREIGN KEY(personId) REFERENCES person_records(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_speaker_embeddings_personId ON speaker_embeddings(personId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_speaker_embeddings_capturedAt ON speaker_embeddings(capturedAt)"
                )
            }
        }

        /**
         * Migration 4 → 5: add knowledge system tables
         * (knowledge_sources, wiki_pages, fact_records, page_links, knowledge_log, contradictions).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_sources (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceType TEXT NOT NULL, createdAt INTEGER NOT NULL, rawText TEXT NOT NULL, metadataJson TEXT, retentionClass TEXT NOT NULL, compiledAt INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_sources_createdAt ON knowledge_sources(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_sources_retentionClass ON knowledge_sources(retentionClass)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_sources_compiledAt ON knowledge_sources(compiledAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS wiki_pages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, pageType TEXT NOT NULL, title TEXT NOT NULL, titleNormalized TEXT NOT NULL, summary TEXT NOT NULL DEFAULT '', body TEXT, updatedAt INTEGER NOT NULL, confidence REAL NOT NULL DEFAULT 0.5, status TEXT NOT NULL DEFAULT 'ACTIVE', sourceCount INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_wiki_pages_titleNormalized ON wiki_pages(titleNormalized)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wiki_pages_pageType ON wiki_pages(pageType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wiki_pages_status ON wiki_pages(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_wiki_pages_updatedAt ON wiki_pages(updatedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS fact_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, pageId INTEGER NOT NULL, subject TEXT NOT NULL, predicate TEXT NOT NULL, objectValue TEXT NOT NULL, confidence REAL NOT NULL DEFAULT 0.8, sourceId INTEGER, createdAt INTEGER NOT NULL, supersededByFactId INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_fact_records_pageId ON fact_records(pageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_fact_records_predicate ON fact_records(predicate)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_fact_records_supersededByFactId ON fact_records(supersededByFactId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS page_links (fromPageId INTEGER NOT NULL, toPageId INTEGER NOT NULL, linkType TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(fromPageId, toPageId, linkType))")
                db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, createdAt INTEGER NOT NULL, operationType TEXT NOT NULL, summary TEXT NOT NULL, affectedPageIds TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_log_createdAt ON knowledge_log(createdAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS contradictions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, pageId INTEGER NOT NULL, oldFactId INTEGER NOT NULL, newFactId INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'UNRESOLVED', createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_contradictions_pageId ON contradictions(pageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_contradictions_status ON contradictions(status)")
            }
        }

        /**
         * Migration 5 → 6: add pending_followups table for conversational check-ins.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_followups (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "topic TEXT NOT NULL, " +
                    "promptTemplate TEXT NOT NULL, " +
                    "dueAt INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "status TEXT NOT NULL DEFAULT 'PENDING', " +
                    "lastAttemptAt INTEGER NOT NULL DEFAULT 0, " +
                    "attemptCount INTEGER NOT NULL DEFAULT 0, " +
                    "expiresAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_followups_status ON pending_followups(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_followups_dueAt ON pending_followups(dueAt)")
            }
        }

        /**
         * Migration 6 → 7: add brain_events and brain_patterns tables for
         * the behavioural learning system (Jarvis Brain Spec v1).
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS brain_events (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type           TEXT    NOT NULL,
                        timestamp      INTEGER NOT NULL,
                        hourOfDay      INTEGER NOT NULL,
                        minuteOfHour   INTEGER NOT NULL,
                        dayOfWeek      INTEGER NOT NULL,
                        isWeekend      INTEGER NOT NULL,
                        locationState  TEXT    NOT NULL,
                        batteryPct     INTEGER NOT NULL,
                        isCharging     INTEGER NOT NULL,
                        screenOn       INTEGER NOT NULL,
                        bluetoothDevice TEXT,
                        packageName    TEXT,
                        extra          TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_brain_events_timestamp ON brain_events(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_brain_events_type ON brain_events(type)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_brain_events_hourOfDay ON brain_events(hourOfDay)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_brain_events_dayOfWeek ON brain_events(dayOfWeek)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS brain_patterns (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        patternKey       TEXT    NOT NULL,
                        patternType      TEXT    NOT NULL,
                        eventType        TEXT    NOT NULL,
                        triggerEventType TEXT,
                        timeWindowStart  TEXT,
                        timeWindowEnd    TEXT,
                        locationContext  TEXT,
                        dayContext       TEXT,
                        occurrenceCount  INTEGER NOT NULL,
                        totalChecks      INTEGER NOT NULL,
                        confidence       REAL    NOT NULL,
                        confidenceLabel  TEXT    NOT NULL,
                        humanDescription TEXT    NOT NULL,
                        lastSeen         INTEGER NOT NULL,
                        createdAt        INTEGER NOT NULL,
                        updatedAt        INTEGER NOT NULL,
                        decayFactor      REAL    NOT NULL DEFAULT 1.0,
                        lastSuggestedAt  INTEGER NOT NULL DEFAULT 0,
                        acceptCount      INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_brain_patterns_patternKey ON brain_patterns(patternKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_brain_patterns_eventType ON brain_patterns(eventType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_brain_patterns_confidence ON brain_patterns(confidence)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_brain_patterns_updatedAt ON brain_patterns(updatedAt)")
            }
        }

        /**
         * Migration 7 → 8: add recent_guests table for cross-session guest memory.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recent_guests (
                        id                     INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        displayName            TEXT    NOT NULL,
                        displayNameNormalized  TEXT    NOT NULL,
                        lastSeenAt             INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_recent_guests_displayNameNormalized ON recent_guests(displayNameNormalized)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_recent_guests_lastSeenAt ON recent_guests(lastSeenAt)"
                )
            }
        }

        fun getInstance(context: Context): JarvisDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
