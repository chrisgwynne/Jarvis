package com.jarvis.assistant.notifications

/**
 * RecentMessageContext — thread-safe singleton that tracks the last messaging
 * interaction so conversational follow-ups work without re-stating the contact.
 *
 * Updated by [JarvisNotificationListener] whenever a messaging notification
 * arrives.  Consumed by [ReplyNotificationTool] and [ReadNotificationsTool]
 * to resolve implicit targets like "reply yes" or "read that again".
 *
 * TTL: context expires after 30 minutes to prevent stale replies to old threads.
 */
object RecentMessageContext {

    private const val TTL_MS = 30 * 60 * 1000L  // 30 minutes

    data class MessageSnapshot(
        val sender: String,
        val appName: String,
        val packageName: String,
        val text: String,
        val timestampMs: Long,
        /** The buffered entry that can be used to send a reply. */
        val replyableEntry: NotificationEntry?,
    )

    @Volatile private var snapshot: MessageSnapshot? = null
    private val lock = Any()

    /** Update context from a newly received messaging notification. */
    fun update(entry: NotificationEntry) = synchronized(lock) {
        snapshot = MessageSnapshot(
            sender        = entry.sender.ifBlank { entry.title },
            appName       = entry.appName.ifBlank { MessagingAppCapabilityRegistry.displayName(entry.packageName) },
            packageName   = entry.packageName,
            text          = entry.text,
            timestampMs   = entry.postedAt,
            replyableEntry = if (entry.canReply) entry else null,
        )
    }

    /**
     * Returns the current snapshot if it is within TTL, null otherwise.
     * Callers must treat null as "no active thread" and ask the user to
     * specify a contact.
     */
    fun get(): MessageSnapshot? = synchronized(lock) {
        val s = snapshot ?: return null
        if (System.currentTimeMillis() - s.timestampMs > TTL_MS) {
            snapshot = null
            return null
        }
        s
    }

    /** Returns the most-recent replyable entry if within TTL. */
    fun getLastReplyable(): NotificationEntry? = get()?.replyableEntry

    /** Returns the last sender name if within TTL. */
    fun getLastSender(): String? = get()?.sender?.takeIf { it.isNotBlank() }

    /** Returns the last app name if within TTL. */
    fun getLastAppName(): String? = get()?.appName?.takeIf { it.isNotBlank() }

    /** Explicitly clear context — e.g. after user confirms a reply was sent. */
    fun clear() = synchronized(lock) { snapshot = null }
}
