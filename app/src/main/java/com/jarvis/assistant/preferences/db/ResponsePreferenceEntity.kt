package com.jarvis.assistant.preferences.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jarvis.assistant.preferences.PreferenceRuleType
import com.jarvis.assistant.preferences.PreferredLength
import com.jarvis.assistant.preferences.ResponseDomain
import com.jarvis.assistant.preferences.ResponsePreference

@Entity(
    tableName = "response_preferences",
    indices = [
        Index("domain"),
        Index("enabled"),
    ]
)
data class ResponsePreferenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val domain: String,
    val ruleType: String,
    /** JSON array: ["condition","temperature"] */
    val includeFieldsJson: String,
    /** JSON array: ["humidity","wind"] */
    val excludeFieldsJson: String,
    val preferredLength: String,
    /** JSON array: ordered field names */
    val preferredOrderJson: String,
    val exampleFormat: String?,
    val appliesToVoice: Boolean,
    val appliesToText: Boolean,
    val confidence: Float,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceUtterance: String,
    val enabled: Boolean,
) {
    fun toDomain(): ResponsePreference = ResponsePreference(
        id = id,
        domain = ResponseDomain.valueOf(domain),
        ruleType = PreferenceRuleType.valueOf(ruleType),
        includeFields = includeFieldsJson.toStringList(),
        excludeFields = excludeFieldsJson.toStringList(),
        preferredLength = PreferredLength.valueOf(preferredLength),
        preferredOrder = preferredOrderJson.toStringList(),
        exampleFormat = exampleFormat,
        appliesToVoice = appliesToVoice,
        appliesToText = appliesToText,
        confidence = confidence,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sourceUtterance = sourceUtterance,
        enabled = enabled,
    )

    companion object {
        fun from(pref: ResponsePreference): ResponsePreferenceEntity = ResponsePreferenceEntity(
            id = pref.id,
            domain = pref.domain.name,
            ruleType = pref.ruleType.name,
            includeFieldsJson = pref.includeFields.toJson(),
            excludeFieldsJson = pref.excludeFields.toJson(),
            preferredLength = pref.preferredLength.name,
            preferredOrderJson = pref.preferredOrder.toJson(),
            exampleFormat = pref.exampleFormat,
            appliesToVoice = pref.appliesToVoice,
            appliesToText = pref.appliesToText,
            confidence = pref.confidence,
            createdAt = pref.createdAt,
            updatedAt = pref.updatedAt,
            sourceUtterance = pref.sourceUtterance,
            enabled = pref.enabled,
        )
    }
}

private fun List<String>.toJson(): String {
    if (isEmpty()) return "[]"
    return joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }
}

private fun String.toStringList(): List<String> {
    val trimmed = trim()
    if (trimmed == "[]" || trimmed.isEmpty()) return emptyList()
    return try {
        val inner = trimmed.trim('[', ']')
        inner.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
}
