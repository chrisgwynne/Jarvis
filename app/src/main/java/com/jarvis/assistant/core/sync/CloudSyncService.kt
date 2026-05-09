package com.jarvis.assistant.core.sync

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.jarvis.assistant.core.routines.SavedRoutineDao
import com.jarvis.assistant.core.routines.SavedRoutineEntity
import com.jarvis.assistant.memory.db.dao.MemoryFactDao
import com.jarvis.assistant.memory.db.entity.FactCategory
import com.jarvis.assistant.memory.db.entity.MemoryFact
import com.jarvis.assistant.util.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * CloudSyncService — optional, opt-in Firebase sync for the tables that
 * most benefit from following the user across devices:
 *   • memory_facts     (profile: name, preferences, facts the LLM stored)
 *   • saved_routines   (the user's custom automations)
 *
 * Deliberately kept narrow:
 *   • Device-specific data (speaker embeddings, decision traces, driving
 *     mode state, wake-word models) stays local.
 *   • Sensitive credentials (API keys in EncryptedSharedPreferences) stay
 *     local — the Android KeyStore master key does NOT travel.
 *   • Short-lived state (expectations, conversation threads, recent event
 *     buffer) stays local; sync latency would make it stale anyway.
 *
 * Architecture:
 *   • Runtime-configured. No google-services.json at build time. User
 *     enters Firebase API key + app ID + project ID in Settings, signs
 *     in with email/password; [start] initialises [FirebaseApp] via
 *     [FirebaseOptions] and begins a 10-minute sync loop.
 *   • Two-way last-write-wins by per-row timestamp. A row whose local
 *     timestamp is newer than the remote wins, and vice versa.
 *   • Per-user namespacing: /users/{uid}/memory_facts/{factKey} and
 *     /users/{uid}/saved_routines/{nameNormalized}.
 *
 * Lifecycle:
 *   [start] safe to call repeatedly; a second call while running is a
 *   no-op. [stop] cancels the loop without signing out.
 *   [signIn] / [signOut] manage the FirebaseAuth session.
 */
class CloudSyncService(
    private val context: Context,
    private val settings: SettingsStore,
    private val memoryFactDao: MemoryFactDao,
    private val savedRoutineDao: SavedRoutineDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val intervalMs: Long = 10 * 60 * 1000L,
) {

    companion object {
        private const val TAG = "CloudSyncService"
        private const val FIREBASE_APP_NAME = "jarvis"
        private const val COLL_FACTS = "memory_facts"
        private const val COLL_ROUTINES = "saved_routines"
    }

    sealed class Status {
        object Disabled : Status()
        object NotConfigured : Status()
        object SignedOut : Status()
        data class SignedIn(val email: String) : Status()
        data class Error(val message: String) : Status()
    }

    private var loopJob: Job? = null
    @Volatile private var firebaseApp: FirebaseApp? = null

    fun status(): Status {
        if (!settings.cloudSyncEnabled) return Status.Disabled
        if (!settings.firebaseConfigured()) return Status.NotConfigured
        val app = firebaseApp ?: return Status.SignedOut
        val user = FirebaseAuth.getInstance(app).currentUser ?: return Status.SignedOut
        return Status.SignedIn(user.email ?: user.uid)
    }

    /**
     * Initialise the FirebaseApp if not already, then start the periodic
     * sync loop. Does nothing when sync is disabled or not configured.
     */
    fun start() {
        if (loopJob?.isActive == true) return
        if (!settings.cloudSyncEnabled) {
            Log.d(TAG, "cloudSyncEnabled=false — not starting")
            return
        }
        if (!ensureFirebaseApp()) return
        loopJob = scope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) { Log.w(TAG, "tick failed: ${e.message}") }
                delay(intervalMs)
            }
        }
        Log.i(TAG, "cloud sync started (interval=${intervalMs}ms)")
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    suspend fun signIn(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        if (!ensureFirebaseApp()) return@withContext Result.failure(IllegalStateException("Firebase not configured"))
        try {
            val app = firebaseApp!!
            val result = FirebaseAuth.getInstance(app)
                .signInWithEmailAndPassword(email.trim(), password).await()
            val uid = result.user?.uid ?: return@withContext Result.failure(IllegalStateException("No UID"))
            settings.cloudSyncEmail = email.trim()
            Result.success(uid)
        } catch (e: Exception) {
            Log.w(TAG, "signIn failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun createAccount(email: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        if (!ensureFirebaseApp()) return@withContext Result.failure(IllegalStateException("Firebase not configured"))
        try {
            val app = firebaseApp!!
            val result = FirebaseAuth.getInstance(app)
                .createUserWithEmailAndPassword(email.trim(), password).await()
            val uid = result.user?.uid ?: return@withContext Result.failure(IllegalStateException("No UID"))
            settings.cloudSyncEmail = email.trim()
            Result.success(uid)
        } catch (e: Exception) {
            Log.w(TAG, "createAccount failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun signOut() {
        firebaseApp?.let { FirebaseAuth.getInstance(it).signOut() }
    }

    /** Trigger a sync immediately outside the periodic loop. */
    suspend fun syncNow(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val count = tick()
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun ensureFirebaseApp(): Boolean {
        firebaseApp?.let { return true }
        if (!settings.firebaseConfigured()) {
            Log.d(TAG, "firebase not configured — skipping init")
            return false
        }
        val options = FirebaseOptions.Builder()
            .setApiKey(settings.firebaseApiKey)
            .setApplicationId(settings.firebaseAppId)
            .setProjectId(settings.firebaseProjectId)
            .apply {
                settings.firebaseDbUrl.takeIf { it.isNotBlank() }?.let { setDatabaseUrl(it) }
            }
            .build()
        val existing = runCatching { FirebaseApp.getInstance(FIREBASE_APP_NAME) }.getOrNull()
        firebaseApp = existing ?: FirebaseApp.initializeApp(context.applicationContext, options, FIREBASE_APP_NAME)
        Log.d(TAG, "Firebase app initialised (project=${settings.firebaseProjectId})")
        return true
    }

    /**
     * One round-trip. Returns the number of rows exchanged (up or down)
     * for logging. Silently skips if no user is signed in.
     */
    private suspend fun tick(): Int {
        val app = firebaseApp ?: return 0
        val user = FirebaseAuth.getInstance(app).currentUser ?: run {
            Log.v(TAG, "no user signed in — skipping tick")
            return 0
        }
        val firestore = FirebaseFirestore.getInstance(app)
        val since = settings.cloudSyncLastMs
        val nowMs = System.currentTimeMillis()
        var exchanged = 0

        exchanged += syncMemoryFacts(firestore, user.uid, since)
        exchanged += syncSavedRoutines(firestore, user.uid, since)

        settings.cloudSyncLastMs = nowMs
        Log.d(TAG, "tick: exchanged $exchanged rows (lastMs → $nowMs)")
        return exchanged
    }

    // ── memory_facts ─────────────────────────────────────────────────────────

    private suspend fun syncMemoryFacts(
        firestore: FirebaseFirestore,
        uid: String,
        since: Long,
    ): Int {
        val collection = firestore.collection("users").document(uid).collection(COLL_FACTS)
        var count = 0

        // Upload local → remote for any row newer than last sync.
        val localFacts = memoryFactDao.getAll()
        for (fact in localFacts.filter { it.lastUpdatedAt > since }) {
            try {
                val doc = collection.document(fact.factKey)
                val remote = doc.get().await()
                val remoteUpdated = remote.getLong("lastUpdatedAt") ?: 0L
                if (fact.lastUpdatedAt > remoteUpdated) {
                    doc.set(
                        mapOf(
                            "factKey" to fact.factKey,
                            "value" to fact.value,
                            "category" to fact.category.name,
                            "lastUpdatedAt" to fact.lastUpdatedAt,
                        )
                    ).await()
                    count++
                }
            } catch (e: Exception) {
                Log.w(TAG, "upload fact ${fact.factKey} failed: ${e.message}")
            }
        }

        // Download remote → local for any doc updated since last sync.
        try {
            val snapshot = collection
                .whereGreaterThan("lastUpdatedAt", since)
                .orderBy("lastUpdatedAt", Query.Direction.ASCENDING)
                .get()
                .await()
            for (doc in snapshot.documents) {
                val key = doc.getString("factKey") ?: continue
                val value = doc.getString("value") ?: continue
                val categoryName = doc.getString("category") ?: "FACT"
                val remoteUpdated = doc.getLong("lastUpdatedAt") ?: continue
                val local = memoryFactDao.getByKey(key)
                if (local == null || remoteUpdated > local.lastUpdatedAt) {
                    val category = runCatching { FactCategory.valueOf(categoryName) }
                        .getOrDefault(FactCategory.FACT)
                    memoryFactDao.upsert(
                        MemoryFact(
                            factKey = key,
                            value = value,
                            category = category,
                            lastUpdatedAt = remoteUpdated,
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "download facts failed: ${e.message}")
        }
        return count
    }

    // ── saved_routines ───────────────────────────────────────────────────────

    private suspend fun syncSavedRoutines(
        firestore: FirebaseFirestore,
        uid: String,
        since: Long,
    ): Int {
        val collection = firestore.collection("users").document(uid).collection(COLL_ROUTINES)
        var count = 0

        val local = savedRoutineDao.listAll()
        for (routine in local) {
            val rowTs = maxOf(routine.createdAtMs, routine.lastRunAtMs ?: 0L)
            if (rowTs <= since) continue
            try {
                val doc = collection.document(routine.nameNormalized)
                val remote = doc.get().await()
                val remoteTs = remote.getLong("updatedAt") ?: 0L
                if (rowTs > remoteTs) {
                    doc.set(
                        mapOf(
                            "name" to routine.name,
                            "nameNormalized" to routine.nameNormalized,
                            "stepsJson" to routine.stepsJson,
                            "createdAtMs" to routine.createdAtMs,
                            "lastRunAtMs" to routine.lastRunAtMs,
                            "runCount" to routine.runCount,
                            "updatedAt" to rowTs,
                        )
                    ).await()
                    count++
                }
            } catch (e: Exception) {
                Log.w(TAG, "upload routine ${routine.name} failed: ${e.message}")
            }
        }

        try {
            val snapshot = collection
                .whereGreaterThan("updatedAt", since)
                .orderBy("updatedAt", Query.Direction.ASCENDING)
                .get()
                .await()
            for (doc in snapshot.documents) {
                val name = doc.getString("name") ?: continue
                val normalized = doc.getString("nameNormalized") ?: continue
                val stepsJson = doc.getString("stepsJson") ?: continue
                val createdAtMs = doc.getLong("createdAtMs") ?: continue
                val lastRunAtMs = doc.getLong("lastRunAtMs")
                val runCount = (doc.getLong("runCount") ?: 0L).toInt()
                val remoteTs = doc.getLong("updatedAt") ?: continue
                val existing = savedRoutineDao.findByName(normalized)
                val localTs = existing?.let { maxOf(it.createdAtMs, it.lastRunAtMs ?: 0L) } ?: -1L
                if (existing == null || remoteTs > localTs) {
                    savedRoutineDao.upsert(
                        SavedRoutineEntity(
                            id = existing?.id ?: 0,
                            name = name,
                            nameNormalized = normalized,
                            stepsJson = stepsJson,
                            createdAtMs = createdAtMs,
                            lastRunAtMs = lastRunAtMs,
                            runCount = runCount,
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "download routines failed: ${e.message}")
        }
        return count
    }
}
