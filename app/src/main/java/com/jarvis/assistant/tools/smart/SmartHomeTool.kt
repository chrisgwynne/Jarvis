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
 * Supported domains: light, switch, lock, cover (blinds/garage), climate, fan, scene, script.
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
    override val riskClass        = com.jarvis.assistant.tools.framework.RiskClass.MEDIUM

    override fun schema() = ToolSchema(
        name        = name,
        description = "Control smart home devices: turn lights on/off, lock doors, adjust temperature, open/close covers, activate scenes.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "entity_name" to mapOf("type" to "string", "description" to "Name of the device or room (e.g. 'living room lights', 'front door')"),
                "action"      to mapOf("type" to "string", "description" to "Action: on, off, toggle, lock, unlock, open, close, set, dim, scene, script, status"),
                "value"       to mapOf("type" to "string", "description" to "Optional value e.g. brightness 50 or temperature 22")
            ),
            "required" to listOf("entity_name", "action")
        )
    )

    companion object {
        private val SCENE_PATTERN = Regex(
            """(?:activate|run|trigger|set)\s+(?:the\s+)?(?:scene\s+)?(.+?)\s+scene$|(?:activate|set)\s+scene\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        private val SCRIPT_PATTERN = Regex(
            """(?:run|execute|trigger)\s+(?:the\s+)?script\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        private val QUERY_PATTERN = Regex(
            """(?:is|are)\s+(?:the\s+)?(.+?)\s+(?:on|off|locked|unlocked|open|closed|running)\??$|what(?:'s|\s+is)\s+(?:the\s+)?(?:status|state)\s+(?:of\s+)?(.+)""",
            RegexOption.IGNORE_CASE
        )
    }

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
        val t = transcript.trim()

        SCENE_PATTERN.find(t)?.let { m ->
            val name = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            if (name.isNotBlank()) return ToolInput(t, mapOf("action" to "scene", "entity_name" to name, "value" to "", "domain_hint" to "scene"))
        }
        SCRIPT_PATTERN.find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotBlank()) return ToolInput(t, mapOf("action" to "script", "entity_name" to name, "value" to "", "domain_hint" to "script"))
        }
        QUERY_PATTERN.find(t)?.let { m ->
            val name = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            if (name.isNotBlank()) return ToolInput(t, mapOf("action" to "status", "entity_name" to name, "value" to "", "domain_hint" to ""))
        }

        val lower = t.lowercase()
        val hasAction = ACTION_WORDS.containsMatchIn(lower) ||
            lower.startsWith("turn ") || lower.startsWith("switch ")
        if (!hasAction) return null

        val m = ACTION_REGEX.find(t) ?: return null
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
            lower.contains("set")      -> "set"
            else                       -> "on"
        }

        val valueMatch = Regex("""\b(\d+(?:\.\d+)?)\s*(?:%|degrees?|°)?""").find(t)
        val value = valueMatch?.groupValues?.get(1) ?: ""

        val domainHint = when (action) {
            "lock", "unlock" -> "lock"
            "open", "close"  -> "cover"
            "dim"            -> "light"
            else             -> ""
        }

        return ToolInput(t, mapOf("action" to action, "entity_name" to entityName, "value" to value, "domain_hint" to domainHint))
    }

    override suspend fun execute(input: ToolInput): ToolResult {
        val haBaseUrl = settings.haBaseUrl
        val haToken   = settings.haApiToken
        if (haBaseUrl.isBlank() || haToken.isBlank()) {
            return ToolResult.Failure("Home Assistant isn't set up yet — add the URL and token in Settings.")
        }

        val entityName = input.param("entity_name")
        val action     = input.param("action")
        val value      = input.param("value")
        val domainHint = input.param("domain_hint")

        val client = HomeAssistantClient(haBaseUrl, haToken)
        val entities = client.getStates()
        if (entities.isEmpty()) {
            return ToolResult.Failure("Can't reach Home Assistant — check the URL and token.")
        }

        val match = findBestEntity(entityName, entities, domainHint)
            ?: return ToolResult.Failure("No '$entityName' in your setup.")

        if (action == "status") {
            val state = client.getEntityState(match.entityId)
            val stateStr = state?.state ?: match.state
            return ToolResult.Success("${match.friendlyName} is ${describeState(match.domain, stateStr)}.")
        }

        return try {
            dispatchAction(client, match, action, value)
            ToolResult.Success(buildSuccessMessage(action, match, value))
        } catch (e: Exception) {
            Log.w("SmartHomeTool", "HA service call failed: ${e.message}")
            ToolResult.Failure("${match.friendlyName} didn't respond.")
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
                val extras: Map<String, Any> = when {
                    value.isNotBlank() && domain == "light" ->
                        value.toIntOrNull()?.let { mapOf("brightness_pct" to it as Any) } ?: emptyMap()
                    value.isNotBlank() && domain == "fan" ->
                        value.toIntOrNull()?.let { mapOf("percentage" to it as Any) } ?: emptyMap()
                    else -> emptyMap()
                }
                client.callService(domain, svc, entity.entityId, extras)
            }
            "off" -> client.callService(domain, "turn_off", entity.entityId)
            "dim" -> {
                val pct = value.toIntOrNull() ?: 50
                client.callService("light", "turn_on", entity.entityId, mapOf("brightness_pct" to pct))
            }
            "set" -> when (domain) {
                "climate" -> {
                    val temp = value.toFloatOrNull()
                    if (temp != null) {
                        client.callService("climate", "set_temperature", entity.entityId,
                            mapOf("temperature" to temp))
                    } else if (value.isNotBlank()) {
                        client.callService("climate", "set_hvac_mode", entity.entityId,
                            mapOf("hvac_mode" to value.lowercase()))
                    }
                }
                "fan" -> {
                    val pct = value.toIntOrNull()
                    if (pct != null) {
                        client.callService("fan", "set_percentage", entity.entityId,
                            mapOf("percentage" to pct))
                    } else {
                        client.callService("fan", "turn_on", entity.entityId)
                    }
                }
                else -> client.callService(domain, "turn_on", entity.entityId)
            }
            "lock"   -> client.callService("lock", "lock",   entity.entityId)
            "unlock" -> client.callService("lock", "unlock", entity.entityId)
            "open"   -> client.callService("cover", "open_cover",  entity.entityId)
            "close"  -> client.callService("cover", "close_cover", entity.entityId)
            "scene"  -> client.callService("scene",  "turn_on", entity.entityId)
            "script" -> client.callService("script", "turn_on", entity.entityId)
            else     -> client.callService(domain, "turn_on", entity.entityId)
        }
    }

    private fun buildSuccessMessage(
        action: String,
        entity: HomeAssistantClient.HaEntity,
        value: String
    ): String = when (action) {
        "on"     -> "Turned on ${entity.friendlyName}."
        "off"    -> "Turned off ${entity.friendlyName}."
        "toggle" -> "Toggled ${entity.friendlyName}."
        "lock"   -> "Locked ${entity.friendlyName}."
        "unlock" -> "Unlocked ${entity.friendlyName}."
        "open"   -> "Opened ${entity.friendlyName}."
        "close"  -> "Closed ${entity.friendlyName}."
        "dim"    -> if (value.isNotBlank()) "Dimmed ${entity.friendlyName} to $value%." else "Dimmed ${entity.friendlyName}."
        "set"    -> when {
            entity.domain == "climate" && value.toFloatOrNull() != null -> "Set ${entity.friendlyName} to ${value}°."
            entity.domain == "climate" && value.isNotBlank() -> "Set ${entity.friendlyName} to $value mode."
            entity.domain == "fan" && value.isNotBlank() -> "Set ${entity.friendlyName} to $value%."
            else -> "Updated ${entity.friendlyName}."
        }
        "scene"  -> "Activated scene ${entity.friendlyName}."
        "script" -> "Ran script ${entity.friendlyName}."
        else     -> "Updated ${entity.friendlyName}."
    }

    private fun describeState(domain: String, state: String): String = when {
        domain == "lock"    -> if (state == "locked") "locked" else "unlocked"
        domain == "cover"   -> if (state == "open") "open" else "closed"
        domain == "climate" -> "set to $state"
        domain == "fan"     -> if (state == "on") "on" else "off"
        state == "on"       -> "on"
        state == "off"      -> "off"
        else                -> state
    }

    private fun findBestEntity(
        query: String,
        entities: List<HomeAssistantClient.HaEntity>,
        domainHint: String = ""
    ): HomeAssistantClient.HaEntity? {
        val pool = if (domainHint.isNotBlank()) {
            entities.filter { it.domain == domainHint }.takeIf { it.isNotEmpty() } ?: entities
        } else entities
        val q = query.lowercase()
        pool.firstOrNull { q in it.friendlyName.lowercase() }?.let { return it }
        return pool.maxByOrNull { jaroWinkler(q, it.friendlyName.lowercase()) }
            ?.takeIf { jaroWinkler(q, it.friendlyName.lowercase()) > 0.7 }
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
