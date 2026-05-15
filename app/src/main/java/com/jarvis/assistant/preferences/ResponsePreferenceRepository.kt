package com.jarvis.assistant.preferences

import com.jarvis.assistant.preferences.db.ResponsePreferenceDao
import com.jarvis.assistant.preferences.db.ResponsePreferenceEntity

class ResponsePreferenceRepository(private val dao: ResponsePreferenceDao) {

    suspend fun save(pref: ResponsePreference): ResponsePreference {
        // One active preference per domain — disable any existing ones first
        dao.getAllByDomain(pref.domain.name).forEach { existing ->
            if (existing.enabled) dao.setEnabled(existing.id, false)
        }
        val id = dao.insert(ResponsePreferenceEntity.from(pref))
        return pref.copy(id = id)
    }

    suspend fun getActive(domain: ResponseDomain): ResponsePreference? =
        dao.getActiveByDomain(domain.name)?.toDomain()

    suspend fun getAll(): List<ResponsePreference> =
        dao.getAll().map { it.toDomain() }

    suspend fun getAllByDomain(domain: ResponseDomain): List<ResponsePreference> =
        dao.getAllByDomain(domain.name).map { it.toDomain() }

    suspend fun resetDomain(domain: ResponseDomain) = dao.deleteByDomain(domain.name)

    suspend fun resetAll() = dao.deleteAll()

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
}
