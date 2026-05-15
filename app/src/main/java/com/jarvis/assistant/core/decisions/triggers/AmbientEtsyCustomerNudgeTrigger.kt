package com.jarvis.assistant.core.decisions.triggers

import com.jarvis.assistant.ambient.AmbientContextScorer
import com.jarvis.assistant.ambient.RecentAppOpen
import com.jarvis.assistant.core.context.AgentContext
import com.jarvis.assistant.core.decisions.Candidate
import com.jarvis.assistant.core.decisions.Trigger
import com.jarvis.assistant.core.events.Event
import com.jarvis.assistant.proactive.ProactiveEventType

/**
 * AmbientEtsyCustomerNudgeTrigger — fires when the user opens a tracked
 * business/commerce app (Etsy, Shopify, etc.) while unread customer
 * messages or notifications are waiting.
 *
 * Example: "You've opened Etsy. You've got 2 customer messages."
 */
class AmbientEtsyCustomerNudgeTrigger : Trigger {
    override val id: String = "ambient_etsy_customer_nudge"
    override val actionClass: String = "AMBIENT_APP_NUDGE"

    override fun match(ctx: AgentContext, recentEvents: List<Event>): Candidate? {
        val s = ctx.ambient
        if (!ctx.ambient.recentAppOpens.any { it.packageName in COMMERCE_APPS && isRecent(it, ctx.nowMs) }) return null

        val customerMsgs = s.unreadCustomerMessages
        val notifCount   = ctx.proactive.unreadNotificationCount
        if (customerMsgs == 0 && notifCount == 0) return null

        val openedApp = s.recentAppOpens
            .lastOrNull { it.packageName in COMMERCE_APPS && isRecent(it, ctx.nowMs) }
            ?: return null
        val appName = APP_NAMES[openedApp.packageName] ?: "this app"

        val msgCount = if (customerMsgs > 0) customerMsgs else notifCount
        val msgLabel = if (msgCount == 1) "a message" else "$msgCount messages"
        val spokenText = "You've opened $appName. You've still got $msgLabel."

        val (urgency, annoyance) = AmbientContextScorer.toScores(
            AmbientContextScorer.score(confidence = 0.75f)
        )

        return Candidate(
            triggerId    = id,
            eventType    = ProactiveEventType.AMBIENT_APP_CONTEXT_NUDGE,
            title        = "Commerce app opened with pending messages",
            spokenText   = spokenText,
            urgency      = urgency,
            relevance    = 0.80f,
            confidence   = 0.75f,
            annoyanceCost = annoyance,
            dedupeKey    = "ambient_etsy_nudge_${ctx.nowMs / (30 * 60_000L)}",
            actionClass  = actionClass,
            metadata     = mapOf("app" to appName, "count" to msgCount.toString()),
        )
    }

    private fun isRecent(open: RecentAppOpen, nowMs: Long) =
        nowMs - open.openedAtMs < RECENCY_MS

    companion object {
        private const val RECENCY_MS = 10 * 60_000L
        private val COMMERCE_APPS = setOf("com.etsy.android", "com.shopify.mobile")
        private val APP_NAMES = mapOf(
            "com.etsy.android"    to "Etsy",
            "com.shopify.mobile"  to "Shopify",
        )
    }
}
