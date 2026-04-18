package com.jarvis.assistant.tools.smart

import android.util.Log
import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema
import com.jarvis.assistant.util.SettingsStore

/**
 * SmartHomeTool — controls Home Assistant entities via voice.
 *
 * Supported domains: light, switch, lock, cover (blinds/garage), climate.
 * Entity matching uses Jaro-Winkler distance for fuzzy name recognition
 * ("living room lights" → entity "light.living_room_ceiling").
 *
 * Returns null from matches() when HA is not configured so the tool is
 * effectively disabled without a base URL and token.
 */
class SmartHomeTool(private val settings: SettingsStore) : Tool {

    override val name             = "smart_home"
    override val description      = "Control smart home devices via Home Assistant"
    override val requiresNetwork  = true

    override fun schema() = ToolSchema(
        name        = name,
        description = "Control smart home devices: turn lights on/off, lock doors, adjust temperature, open/close covers.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "entity_name" to mapOf("type" to "string", "description" to "Name of the device or room (e.g. 'living room lights', 'front door')"),
                "action"      to mapOf("type" to "string", "description" to "Action: on, off, toggle, lock, unlock, open, close, set"),
                "value"       to mapOf("type" to "string", "description" to "Optional value e.g. brightness 50 or temperature 22")
            ),
            "required" to listOf("entity_name", "action")
        )
    )

    private val ACTION_REGEX = Regex(
        """(?:turn|switch|set|dim|lock|unlock|open|close|toggle)\s+(?:the\s+)?(.+?)(?:\s+(?:on|off|to\s+\d+%?|on\s+to\s+\d+%?))?$""",
        RegexOption.IGNORE_CASE
    )
    private val ACTION_WORDS = Regex(
        """^(turn\s+on|turn\s+off|toggle|lock|unlock|open|close|dim|set)\b""",
        RegexOption.IGNORE_CASE
    )

    override fun matches(transcript: String): ToolInput? {
        if (settings.haBaseUrl.isBlank() || settings.haApiToken.isBlank()) return null
        val lower = transcript.lowercase()
        val hasAction = ACTION_WORDS.containsMatchIn(lower) ||
            lower.startsWith("turn ") || lower.startsWith("switch ")
        if (!hasAction) return null

        val m = ACTION_REGEX.find(transcript.trim()) ?: return null
        val entityName = m.groupValues[1].trim()
        if (entityName.isBlank()) return null

        val action = when {
            lower.contains("turn on")  -> "on"
            lower.contains("turn off") -> "off"
            lower.contains("toggle")   -> "toggle"
            lower.contains("lock")     -> "lock"
            lower.contains("unlock")   -> "unlock"
            lower.contains("open")     -> "open"
            lower.contains("close")    -> "close"
            lower.contains("dim")      -> "dim"
            else                       -> "on"
        }

        // Extract numeric value if present (brightness, temperature)
        val valueMatch = Regex("""\b(\d+)\s*%?""").find(transcript)
        val value = valueMatch?.groupValues?.get(1) ?: ""

        return ToolInput(transcript, mapOf("entity_name" to entityName, "action" to action, "value" to value))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val haBaseUrl = settings.haBaseUrl
        val haToken   = settings.haApiToken
        if (haBaseUrl.isBlank() || haToken.isBlank()) {
            return ToolResult.Failure("Home Assistant is not configured. Add the URL and token in Settings.")
        }

        val entityName = input.param("entity_name")
        val action     = input.param("action")
        val value      = input.param("value")

        val client = HomeAssistantClient(haBaseUrl, haToken)
        val entities = client.getStates()
        if (entities.isEmpty()) {
            return ToolResult.Failure("Couldn't reach Home Assistant. Check the URL and token in Settings.")
        }

        val match = findBestEntity(entityName, entities)
            ?: return ToolResult.Failure("I couldn't find '$entityName' in your smart home.")

        return try {
            dispatchAction(client, match, action, value)
            val verb = actionVerb(action)
            ToolResult.Success("$verb ${match.friendlyName}.")
        } catch (e: Exception) {
            Log.w("SmartHomeTool", "HA service call failed: ${e.message}")
            ToolResult.Failure("Couldn't control ${match.friendlyName}: ${e.message}")
        }
    }

    private suspend fun dispatchAction(
        client: HomeAssistantClient,
        entity: HomeAssistantClient.HaEntity,
        action: String,
        value: String
    ) {
        val domain = entity.domain
        when (action) {
            "on", "toggle" -> {
                val svc = if (action == "toggle") "toggle" else "turn_on"
                val extras = if (value.isNotBlank() && domain == "light")
                    mapOf("brightness_pct" to value.toIntOrNull())
                        .filterValues { it != null }
                        .mapValues { it.value as Any }
                else emptyMap()
                client.callService(domain, svc, entity.entityId, extras)
            }
            "off" -> client.callService(domain, "turn_off", entity.entityId)
            "dim" -> {
                val pct = value.toIntOrNull() ?: 50
                client.callService("light", "turn_on", entity.entityId, mapOf("brightness_pct" to pct))
            }
            "lock"   -> client.callService("lock", "lock",   entity.entityId)
            "unlock" -> client.callService("lock", "unlock", entity.entityId)
            "open"   -> client.callService("cover", "open_cover",  entity.entityId)
            "close"  -> client.callService("cover", "close_cover", entity.entityId)
            else     -> client.callService(domain, "turn_on", entity.entityId)
        }
    }

    private fun findBestEntity(
        query: String,
        entities: List<HomeAssistantClient.HaEntity>
    ): HomeAssistantClient.HaEntity? {
        val q = query.lowercase()
        // Exact substring match first
        entities.firstOrNull { q in it.friendlyName.lowercase() }?.let { return it }
        // Fuzzy Jaro-Winkler
        return entities.maxByOrNull { jaroWinkler(q, it.friendlyName.lowercase()) }
            ?.takeIf { jaroWinkler(q, it.friendlyName.lowercase()) > 0.7 }
    }

    private fun actionVerb(action: String) = when (action) {
        "on"     -> "Turned on"
        "off"    -> "Turned off"
        "toggle" -> "Toggled"
        "lock"   -> "Locked"
        "unlock" -> "Unlocked"
        "open"   -> "Opened"
        "close"  -> "Closed"
        "dim"    -> "Dimmed"
        else     -> "Updated"
    }

    // ── Jaro-Winkler string similarity ────────────────────────────────────────

    private fun jaroWinkler(s1: String, s2: String): Double {
        val jaro = jaro(s1, s2)
        val prefix = (0 until minOf(4, s1.length, s2.length)).takeWhile { s1[it] == s2[it] }.size
        return jaro + prefix * 0.1 * (1.0 - jaro)
    }

    private fun jaro(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val range = maxOf(0, maxOf(s1.length, s2.length) / 2 - 1)
        val s1m = BooleanArray(s1.length)
        val s2m = BooleanArray(s2.length)
        var matches = 0
        for (i in s1.indices) {
            val lo = maxOf(0, i - range)
            val hi = minOf(i + range + 1, s2.length)
            for (j in lo until hi) {
                if (!s2m[j] && s1[i] == s2[j]) { s1m[i] = true; s2m[j] = true; matches++; break }
            }
        }
        if (matches == 0) return 0.0
        var t = 0; var k = 0
        for (i in s1.indices) if (s1m[i]) {
            while (!s2m[k]) k++
            if (s1[i] != s2[k]) t++
            k++
        }
        return (matches.toDouble() / s1.length + matches.toDouble() / s2.length +
                (matches - t / 2.0) / matches) / 3.0
    }
}
