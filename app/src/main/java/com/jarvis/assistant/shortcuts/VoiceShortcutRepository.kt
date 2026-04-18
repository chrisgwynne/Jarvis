package com.jarvis.assistant.shortcuts

import com.jarvis.assistant.shortcuts.db.VoiceShortcut
import com.jarvis.assistant.shortcuts.db.VoiceShortcutDao

class VoiceShortcutRepository(private val dao: VoiceShortcutDao) {

    suspend fun add(name: String, commands: List<String>): VoiceShortcut {
        val shortcut = VoiceShortcut(
            name              = name.trim(),
            triggerNormalized = name.trim().lowercase(),
            commands          = commands.joinToString("\n")
        )
        dao.insert(shortcut)
        return shortcut
    }

    suspend fun getAll(): List<VoiceShortcut> = dao.getAll()

    suspend fun findByTrigger(phrase: String): VoiceShortcut? =
        dao.findByTrigger(phrase.trim().lowercase())

    suspend fun delete(name: String): Boolean =
        dao.deleteByTrigger(name.trim().lowercase()) > 0

    fun commandsOf(shortcut: VoiceShortcut): List<String> =
        shortcut.commands.split("\n").filter { it.isNotBlank() }
}
