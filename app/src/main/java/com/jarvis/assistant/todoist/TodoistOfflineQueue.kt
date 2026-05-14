package com.jarvis.assistant.todoist

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * TodoistOfflineQueue — persistent FIFO of pending Todoist mutations.
 *
 * When the device is offline (or Todoist transiently returns 5xx after
 * the client's single retry) we don't want the user's reminder to be
 * lost.  Each create/update is appended here; a periodic / on-demand
 * drain replays them when network is back.
 *
 * Persistence: a single JSON file in the app's filesDir.  This is
 * intentionally lighter than adding another Room entity + migration
 * — there's no schema evolution to worry about, no JOINs needed, and
 * the queue is small (typically a handful of items).
 *
 * Thread-safety: a single re-entrant lock guards the in-memory list +
 * disk file.  All operations are short (<10 ms) so contention is a
 * non-issue.
 */
class TodoistOfflineQueue(context: Context) {

    /** One enqueued operation. */
    data class Entry(
        /** Unique idempotency key — assigned on enqueue, never re-used. */
        val id: String,
        val type: Type,
        val createPayload: TodoistClient.CreateTaskRequest? = null,
        val completeTaskId: String? = null,
        val deleteTaskId: String? = null,
        val updateTaskId: String? = null,
        val updateFields: Map<String, String>? = null,
        val enqueuedAtMs: Long,
        val attempts: Int = 0,
        /** Wall-clock ms of the last drain attempt — used for cooldown. */
        val lastAttemptMs: Long? = null,
        /** Reason for the last failure — surfaced in the diagnostics UI. */
        val lastFailureReason: String? = null,
    ) {
        enum class Type { CREATE, COMPLETE, DELETE, UPDATE }
    }

    private val file: File = File(context.filesDir, "todoist_offline_queue.json")
    private val gson = Gson()
    private val lock = ReentrantLock()
    private val queue = mutableListOf<Entry>()

    init { reloadLocked() }

    fun size(): Int = lock.withLock { queue.size }

    fun snapshot(): List<Entry> = lock.withLock { queue.toList() }

    fun enqueueCreate(req: TodoistClient.CreateTaskRequest): Entry {
        // Idempotency: if an identical CREATE is already pending we
        // return the existing entry rather than enqueue twice.  Identity
        // is (content, dueString, dueDate, dueDatetime, projectId) —
        // priority/labels differences are also considered distinct.
        lock.withLock {
            val existing = queue.firstOrNull {
                it.type == Entry.Type.CREATE &&
                    it.createPayload?.content == req.content &&
                    it.createPayload.dueString == req.dueString &&
                    it.createPayload.dueDate == req.dueDate &&
                    it.createPayload.dueDatetime == req.dueDatetime &&
                    it.createPayload.projectId == req.projectId
            }
            if (existing != null) {
                Log.d(TAG, "[TODOIST_OFFLINE_DEDUPED] id=${existing.id} " +
                    "content=\"${req.content.take(40)}\" — already queued")
                return existing
            }
            val e = Entry(
                id              = UUID.randomUUID().toString(),
                type            = Entry.Type.CREATE,
                createPayload   = req,
                enqueuedAtMs    = System.currentTimeMillis(),
            )
            queue += e
            persistLocked()
            Log.d(TAG, "[TODOIST_OFFLINE_ENQUEUED] type=CREATE id=${e.id} " +
                "content=\"${req.content.take(40)}\" depth=${queue.size}")
            return e
        }
    }

    fun enqueueComplete(taskId: String): Entry {
        val e = Entry(
            id              = UUID.randomUUID().toString(),
            type            = Entry.Type.COMPLETE,
            completeTaskId  = taskId,
            enqueuedAtMs    = System.currentTimeMillis(),
        )
        lock.withLock {
            queue += e
            persistLocked()
        }
        return e
    }

    fun enqueueDelete(taskId: String): Entry {
        val e = Entry(
            id              = UUID.randomUUID().toString(),
            type            = Entry.Type.DELETE,
            deleteTaskId    = taskId,
            enqueuedAtMs    = System.currentTimeMillis(),
        )
        lock.withLock {
            queue += e
            persistLocked()
        }
        return e
    }

    fun remove(id: String) {
        lock.withLock {
            if (queue.removeAll { it.id == id }) persistLocked()
        }
    }

    fun bumpAttempts(id: String, failureReason: String? = null) {
        lock.withLock {
            val idx = queue.indexOfFirst { it.id == id }
            if (idx >= 0) {
                queue[idx] = queue[idx].copy(
                    attempts = queue[idx].attempts + 1,
                    lastAttemptMs = System.currentTimeMillis(),
                    lastFailureReason = failureReason ?: queue[idx].lastFailureReason,
                )
                persistLocked()
            }
        }
    }

    /** Pull every entry's last failure reason for the diagnostics screen. */
    fun lastFailureReasons(): List<Pair<String, String?>> = lock.withLock {
        queue.map { it.id to it.lastFailureReason }
    }

    fun clear() {
        lock.withLock {
            queue.clear()
            persistLocked()
        }
    }

    /**
     * Drain one entry at a time using [executor], removing each on
     * success.  Returns the number of successful drains.  Stops on the
     * first failure so transient outages don't drain the whole queue
     * with retries.
     */
    suspend fun drain(executor: suspend (Entry) -> Boolean): Int {
        var drained = 0
        while (true) {
            val head = lock.withLock { queue.firstOrNull() } ?: break
            val ok = try {
                executor(head)
            } catch (e: Exception) {
                Log.w(TAG, "[TODOIST_OFFLINE_DRAIN_FAILED] id=${head.id} ${e.message}")
                false
            }
            if (!ok) {
                bumpAttempts(head.id)
                Log.w(TAG, "[TODOIST_OFFLINE_DRAIN_HALTED] " +
                    "id=${head.id} attempts=${head.attempts + 1}")
                break
            }
            remove(head.id)
            drained++
            Log.d(TAG, "[TODOIST_OFFLINE_DRAIN_OK] id=${head.id} type=${head.type}")
        }
        return drained
    }

    // ── Persistence ───────────────────────────────────────────────────────

    private fun reloadLocked() = lock.withLock {
        if (!file.exists()) return@withLock
        try {
            val json = file.readText()
            if (json.isBlank()) return@withLock
            val type = object : TypeToken<List<Entry>>() {}.type
            val loaded: List<Entry> = gson.fromJson(json, type) ?: emptyList()
            queue.clear()
            queue.addAll(loaded)
            Log.d(TAG, "[TODOIST_OFFLINE_LOADED] depth=${queue.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reload offline queue: ${e.message} — starting fresh")
        }
    }

    private fun persistLocked() {
        try {
            file.writeText(gson.toJson(queue))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist offline queue: ${e.message}")
        }
    }

    companion object { private const val TAG = "TodoistOfflineQ" }
}
