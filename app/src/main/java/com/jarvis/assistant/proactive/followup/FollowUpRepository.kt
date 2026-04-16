package com.jarvis.assistant.proactive.followup

import android.util.Log

class FollowUpRepository(private val dao: PendingFollowUpDao) {

    companion object {
        private const val TAG = "FollowUpRepository"
        private const val SENT_TIMEOUT_MS = 12 * 3_600_000L  // 12 hours after sending
    }

    /**
     * Schedule a follow-up, skipping it silently if one is already pending for
     * the same topic.  One check-in per meaningful signal — no stacking.
     */
    suspend fun schedule(followUp: PendingFollowUp): Long {
        val existing = dao.countActiveForTopic(followUp.topic)
        if (existing > 0) {
            Log.d(TAG, "Follow-up skipped — already active for topic='${followUp.topic}'")
            return -1L
        }
        val id = dao.insert(followUp)
        Log.d(TAG, "Scheduled follow-up id=$id type=${followUp.type} " +
                   "topic='${followUp.topic}' dueAt=${followUp.dueAt}")
        return id
    }

    suspend fun getDue(): List<PendingFollowUp> =
        dao.getDue(System.currentTimeMillis())

    suspend fun getActive(): List<PendingFollowUp> =
        dao.getActive()

    suspend fun markSent(followUp: PendingFollowUp) =
        dao.update(followUp.copy(
            status        = PendingFollowUp.STATUS_SENT,
            lastAttemptAt = System.currentTimeMillis(),
            attemptCount  = followUp.attemptCount + 1
        ))

    suspend fun markResolved(id: Long) = dao.setStatus(id, PendingFollowUp.STATUS_RESOLVED)
    suspend fun markIgnored(id: Long)  = dao.setStatus(id, PendingFollowUp.STATUS_IGNORED)
    suspend fun markExpired(id: Long)  = dao.setStatus(id, PendingFollowUp.STATUS_EXPIRED)

    /** Expire any PENDING follow-ups past their [PendingFollowUp.expiresAt]. */
    suspend fun expireStale() {
        val now = System.currentTimeMillis()
        dao.getActive().filter { it.expiresAt < now && it.status == PendingFollowUp.STATUS_PENDING }
            .forEach {
                Log.d(TAG, "Expiring stale follow-up id=${it.id} topic='${it.topic}'")
                dao.setStatus(it.id, PendingFollowUp.STATUS_EXPIRED)
            }
    }

    /** Mark SENT follow-ups as IGNORED if they have been sent but never resolved. */
    suspend fun markSentAsIgnored() {
        val cutoff = System.currentTimeMillis() - SENT_TIMEOUT_MS
        dao.getActive()
            .filter { it.status == PendingFollowUp.STATUS_SENT && it.lastAttemptAt < cutoff }
            .forEach {
                Log.d(TAG, "Marking follow-up id=${it.id} as IGNORED (no resolution after send)")
                dao.setStatus(it.id, PendingFollowUp.STATUS_IGNORED)
            }
    }

    /**
     * If [transcript] mentions a topic from any active follow-up, resolve it.
     * Called on every user utterance so natural conversation clears the queue.
     */
    suspend fun maybeResolveFromTranscript(transcript: String) {
        val lower = transcript.lowercase()
        dao.getActive().forEach { fu ->
            if (fu.topic.split(" ").any { word -> word.length > 3 && lower.contains(word) }) {
                Log.d(TAG, "Resolving follow-up id=${fu.id} via natural mention of '${fu.topic}'")
                dao.setStatus(fu.id, PendingFollowUp.STATUS_RESOLVED)
            }
        }
    }

    suspend fun pruneOld(olderThanMs: Long = 7 * 86_400_000L) =
        dao.pruneOld(System.currentTimeMillis() - olderThanMs)

}
