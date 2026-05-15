package com.jarvis.assistant.ambient.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jarvis.assistant.ambient.AmbientEventType
import com.jarvis.assistant.ambient.RoutinePattern

@Entity(
    tableName = "routine_patterns",
    indices = [
        Index("triggerType"),
        Index("confidence"),
        Index("lastSeenMs"),
    ]
)
data class RoutinePatternEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val triggerType: String,
    val usualStartMinute: Int,
    val usualEndMinute: Int,
    val dayOfWeekMask: Int,
    val description: String,
    val followUpAction: String?,
    val confidence: Float,
    val lastSeenMs: Long,
    val seenCount: Int,
    val dismissedCount: Int,
) {
    fun toDomain(): RoutinePattern = RoutinePattern(
        id = id,
        triggerType = AmbientEventType.valueOf(triggerType),
        usualStartMinute = usualStartMinute,
        usualEndMinute = usualEndMinute,
        dayOfWeekMask = dayOfWeekMask,
        description = description,
        followUpAction = followUpAction,
        confidence = confidence,
        lastSeenMs = lastSeenMs,
        seenCount = seenCount,
        dismissedCount = dismissedCount,
    )

    companion object {
        fun from(p: RoutinePattern): RoutinePatternEntity = RoutinePatternEntity(
            id = p.id,
            triggerType = p.triggerType.name,
            usualStartMinute = p.usualStartMinute,
            usualEndMinute = p.usualEndMinute,
            dayOfWeekMask = p.dayOfWeekMask,
            description = p.description,
            followUpAction = p.followUpAction,
            confidence = p.confidence,
            lastSeenMs = p.lastSeenMs,
            seenCount = p.seenCount,
            dismissedCount = p.dismissedCount,
        )
    }
}
