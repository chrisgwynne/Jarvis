package com.jarvis.assistant.tools.smart

import android.util.Log
import com.jarvis.assistant.llm.NetworkClient
import com.google.gson.annotations.SerializedName

/**
 * Thin REST client for a local Home Assistant instance.
 *
 * Uses the long-lived access token stored in SettingsStore (haApiToken).
 * Entity list is cached for 10 minutes to avoid hammering the HA API on every query.
 */
class HomeAssistantClient(
    private val baseUrl: String,
    private val token: String
) {

    companion object {
        private const val TAG = "HomeAssistantClient"
        private const val CACHE_TTL_MS = 10 * 60 * 1_000L
    }

    private val headers get() = mapOf(
        "Authorization" to "Bearer $token",
        "Content-Type"  to "application/json"
    )

    @Volatile private var entityCache: Pair<Long, List<HaEntity>>? = null

    suspend fun getStates(): List<HaEntity> {
        val cached = entityCache
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_TTL_MS) {
            return cached.second
        }
        return try {
            val url  = "${baseUrl.trimEnd('/')}/api/states"
            val body = NetworkClient.get(url, headers)
            val raw  = NetworkClient.gson.fromJson(body, Array<HaStateRaw>::class.java)
            val entities = raw.mapNotNull { it.toEntity() }
            entityCache = Pair(System.currentTimeMillis(), entities)
            Log.d(TAG, "Loaded ${entities.size} HA entities")
            entities
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch HA states: ${e.message}")
            emptyList()
        }
    }

    suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        extras: Map<String, Any> = emptyMap()
    ) {
        try {
            val url      = "${baseUrl.trimEnd('/')}/api/services/$domain/$service"
            val payload  = buildMap<String, Any> {
                put("entity_id", entityId)
                putAll(extras)
            }
            NetworkClient.post(url, headers, NetworkClient.gson.toJson(payload))
            Log.d(TAG, "Called HA service $domain.$service on $entityId")
        } catch (e: Exception) {
            Log.w(TAG, "HA service call failed: ${e.message}")
            throw e
        }
    }

    data class HaEntity(
        val entityId: String,
        val friendlyName: String,
        val state: String,
        val domain: String
    )

    private data class HaStateRaw(
        @SerializedName("entity_id")  val entityId: String?,
        @SerializedName("state")       val state: String?,
        @SerializedName("attributes")  val attributes: Map<String, Any>?
    ) {
        fun toEntity(): HaEntity? {
            val id     = entityId ?: return null
            val domain = id.substringBefore(".")
            val friendly = (attributes?.get("friendly_name") as? String) ?: id
            return HaEntity(id, friendly, state ?: "unknown", domain)
        }
    }
}
