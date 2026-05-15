package com.jarvis.assistant.preferences

/**
 * Individual components of a weather response, used to apply
 * [ResponsePreference] formatting rules without an LLM call.
 *
 * Field names here match the keys in [PreferenceExtractor.WEATHER_FIELDS].
 */
data class WeatherComponents(
    val condition: String,
    val temperature: String,
    val feelsLike: String?,
    val wind: String?,
    val highC: String?,
    val lowC: String?,
    val precipitationMm: Double?,
) {
    /** Default format — same as WeatherTool's original output. */
    fun defaultFormat(): String {
        val feelsStr  = feelsLike?.let { ", feels like $it" } ?: ""
        val windStr   = wind?.let { " Wind is $it." } ?: ""
        val highLow   = if (highC != null && lowC != null) " Today's high is $highC, low is $lowC." else ""
        val rainStr   = precipitationMm?.let {
            if (it > 0.5) " ${String.format("%.1f", it)} mm of rain expected today." else ""
        } ?: ""
        return "$condition. Currently $temperature$feelsStr.$windStr$highLow$rainStr"
    }

    /**
     * Applies [pref] to select / exclude / reorder fields.
     * Returns null if the preference cannot be applied (no recognised fields).
     */
    fun format(pref: ResponsePreference): String? {
        if (!pref.isActive()) return null

        return when (pref.ruleType) {
            PreferenceRuleType.INCLUDE_ONLY -> buildFromFields(pref.includeFields)
            PreferenceRuleType.EXCLUDE      -> {
                val allFields = listOf("condition", "temperature", "feels_like",
                                       "wind", "high_low", "precipitation")
                buildFromFields(allFields - pref.excludeFields.toSet())
            }
            PreferenceRuleType.LENGTH -> when (pref.preferredLength) {
                PreferredLength.BRIEF    -> "$condition. $temperature."
                PreferredLength.DETAILED -> defaultFormat()
                else                     -> null
            }
            else -> null
        }
    }

    private fun buildFromFields(fields: List<String>): String? {
        if (fields.isEmpty()) return null
        val parts = mutableListOf<String>()
        for (field in fields) {
            when (field) {
                "condition"     -> parts.add(condition)
                "temperature"   -> parts.add(temperature)
                "feels_like"    -> feelsLike?.let { parts.add("feels like $it") }
                "wind"          -> wind?.let { parts.add("wind $it") }
                "high_low"      -> if (highC != null && lowC != null) parts.add("high $highC, low $lowC")
                "precipitation" -> precipitationMm?.let {
                    if (it > 0.5) parts.add("${String.format("%.1f", it)} mm rain expected")
                }
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(", ") + "."
    }
}
