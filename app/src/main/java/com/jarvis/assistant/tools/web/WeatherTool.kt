package com.jarvis.assistant.tools.web

import android.Manifest
import android.util.Log
import com.jarvis.assistant.llm.NetworkClient
import com.jarvis.assistant.location.CurrentLocationProvider
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import kotlinx.coroutines.Dispatchers

/**
 * WeatherTool — fetches current conditions + today's forecast from Open-Meteo.
 *
 * Free API, no key required.  Uses [CurrentLocationProvider] for lat/lon.
 * Falls back to a sensible message when location is unavailable.
 *
 * Results are cached for [CACHE_TTL_MS] to avoid hammering the API on follow-up
 * questions ("how cold is it again?").
 */
class WeatherTool(private val locationProvider: CurrentLocationProvider) : Tool {

    override val name = "weather"
    override val description = "Get current weather conditions and forecast from Open-Meteo"
    override val requiresNetwork = true
    override val requiredPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION)

    companion object {
        private const val TAG = "WeatherTool"
        private const val CACHE_TTL_MS = 10 * 60 * 1_000L  // 10 minutes

        private val TRIGGERS = listOf(
            "weather", "forecast", "temperature outside", "how cold", "how hot",
            "rain today", "will it rain", "going to rain", "umbrella",
            "what's it like outside", "what is it like outside",
            "is it raining", "is it sunny", "is it cold", "is it hot",
            "how warm", "chance of rain"
        )
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Get the current weather conditions and today's forecast for the user's location."
    )

    @Volatile private var cachedResult: Pair<Long, String>? = null

    override fun matches(transcript: String): ToolInput? {
        val lower = transcript.lowercase()
        return if (TRIGGERS.any { lower.contains(it) }) ToolInput(transcript) else null
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        // Return cached result if fresh
        cachedResult?.let { (ts, text) ->
            if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                return ToolResult.Success(text)
            }
        }

        val loc = locationProvider.lastResult
        if (loc == null) {
            return ToolResult.Failure("I don't have your location yet. Make sure location permission is granted.")
        }

        return try {
            val spoken = fetchWeather(loc.latitude, loc.longitude)
            cachedResult = Pair(System.currentTimeMillis(), spoken)
            ToolResult.Success(spoken)
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed: ${e.message}", e)
            ToolResult.Failure("I couldn't get the weather right now. Try again in a moment.")
        }
    }

    private suspend fun fetchWeather(lat: Double, lon: Double): String {
        val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code" +
                "&temperature_unit=celsius&wind_speed_unit=kmh&precipitation_unit=mm&timezone=auto" +
                "&forecast_days=1"

        val body = NetworkClient.get(url, emptyMap())
        val root = NetworkClient.gson.fromJson(body, WeatherResponse::class.java)

        val cur  = root.current ?: throw Exception("no current block")
        val daily = root.daily

        val tempC = cur.temperature_2m?.let { "%.0f°C".format(it) } ?: "unknown temperature"
        val feelsC = cur.apparent_temperature?.let { ", feels like %.0f°C".format(it) } ?: ""
        val cond = conditionDescription(cur.weather_code)
        val wind = cur.wind_speed_10m?.let { " Wind is ${"%.0f".format(it)} km/h." } ?: ""

        val highLow = if (daily != null) {
            val hi = daily.temperature_2m_max?.firstOrNull()?.let { "%.0f°C".format(it) }
            val lo = daily.temperature_2m_min?.firstOrNull()?.let { "%.0f°C".format(it) }
            if (hi != null && lo != null) " Today's high is $hi, low is $lo." else ""
        } else ""

        val rain = daily?.precipitation_sum?.firstOrNull()?.let {
            if (it > 0.5) " ${String.format("%.1f", it)} mm of rain expected today." else ""
        } ?: ""

        return "$cond. Currently $tempC$feelsC.$wind$highLow$rain"
    }

    private fun conditionDescription(code: Int?): String = when (code) {
        0            -> "Clear skies"
        1, 2         -> "Mainly clear"
        3            -> "Overcast"
        45, 48       -> "Foggy"
        51, 53, 55   -> "Drizzle"
        61, 63, 65   -> "Rain"
        71, 73, 75   -> "Snow"
        80, 81, 82   -> "Rain showers"
        95           -> "Thunderstorm"
        96, 99       -> "Thunderstorm with hail"
        else         -> "Mixed conditions"
    }

    // ── Wire-format data classes ──────────────────────────────────────────────

    private data class WeatherResponse(
        val current: CurrentBlock?,
        val daily: DailyBlock?
    )

    private data class CurrentBlock(
        val temperature_2m: Double?,
        val apparent_temperature: Double?,
        val precipitation: Double?,
        val weather_code: Int?,
        val wind_speed_10m: Double?
    )

    private data class DailyBlock(
        val temperature_2m_max: List<Double>?,
        val temperature_2m_min: List<Double>?,
        val precipitation_sum: List<Double>?,
        val weather_code: List<Int>?
    )
}
