package com.jarvis.assistant.ambient

/**
 * Point-in-time snapshot of all ambient intelligence the system knows about.
 *
 * Maintained by [AmbientProactiveEventEmitter] and read by ambient triggers
 * via [com.jarvis.assistant.core.context.AgentContext.ambient].
 */
data class AmbientContext(
    /** Coarse location bucket derived from place-learner transitions. */
    val locationBucket: AmbientLocationBucket = AmbientLocationBucket.UNKNOWN,

    /** Apps opened recently (newest first). Used by app-context nudge triggers. */
    val recentAppOpens: List<RecentAppOpen> = emptyList(),

    /**
     * Epoch ms when a car Bluetooth device most recently connected,
     * or null if not connected or connection was too long ago.
     */
    val carBtConnectedMs: Long? = null,

    /**
     * Name of a shop the user is currently near (e.g. "Tesco"),
     * or null if not near any known shop.
     */
    val nearShopName: String? = null,

    /**
     * Todoist task labels that match the user's current location context.
     * Populated when [nearShopName] is non-null.
     */
    val todoistItemsMatchingLocation: List<String> = emptyList(),

    /**
     * Count of unread customer / work messages (Etsy, work Slack, etc.)
     * detected via the notification listener.
     */
    val unreadCustomerMessages: Int = 0,

    /**
     * Friendly names of HA devices that are actively running while the user
     * is detected as away (e.g. "Printer", "Workshop socket").
     */
    val haDevicesRunningAway: List<String> = emptyList(),

    /** Routine patterns learned by [RoutineLearningEngine] with sufficient confidence. */
    val activeRoutinePatterns: List<RoutinePattern> = emptyList(),

    /**
     * Lead-time in minutes that learned patterns suggest for leaving before
     * the next calendar event.  Null when no confident pattern exists.
     */
    val learnedLeaveLeadMinutes: Int? = null,
) {
    companion object {
        val EMPTY = AmbientContext()
    }
}

/** An app that was recently brought to the foreground. */
data class RecentAppOpen(
    val packageName: String,
    val openedAtMs: Long,
)
