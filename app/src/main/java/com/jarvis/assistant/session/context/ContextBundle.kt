package com.jarvis.assistant.session.context

import com.jarvis.assistant.maps.MapsNavigationContextStore
import com.jarvis.assistant.tools.device.RecentAppContextStore
import com.jarvis.assistant.vision.VisualContextStore

/**
 * Aggregates every in-memory context store into a single queryable snapshot.
 *
 * [SessionIntelligenceCoordinator] reads this to resolve what "it / that /
 * there / them" refers to in any given utterance.
 *
 * Add new stores here as they are introduced; the [ContextResolver] maps
 * transcript patterns to which bundle member to consult.
 */
data class ContextBundle(
    val visual: VisualContextStore?,
    val mapsNavigation: MapsNavigationContextStore?,
    val recentApp: RecentAppContextStore?,
    val message: RecentMessageContextStore?,
    val calendar: RecentCalendarContextStore?,
    val homeAssistant: RecentHomeAssistantContextStore?,
    val todoist: RecentTodoistContextStore?,
    val proactive: RecentProactiveContextStore?,
) {
    /** Returns a human-readable summary of which contexts are currently live. */
    fun activeSummary(): String = buildList {
        if (visual?.hasContext == true)       add("visual")
        if (mapsNavigation?.hasContext == true) add("maps-navigation")
        if (recentApp?.hasContext == true)    add("recent-app")
        if (message?.hasContext == true)      add("message")
        if (calendar?.hasContext == true)     add("calendar")
        if (homeAssistant?.hasContext == true) add("home-assistant")
        if (todoist?.hasContext == true)      add("todoist")
        if (proactive?.hasContext == true)    add("proactive")
    }.joinToString(", ").ifEmpty { "none" }
}
