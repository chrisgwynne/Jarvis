package com.jarvis.assistant.shortcuts.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-defined voice shortcut: a trigger phrase that expands to a stored
 * sequence of voice commands (one per line in [commands]).
 */
@Entity(
    tableName = "voice_shortcuts",
    indices = [Index("triggerNormalized", unique = true)]
)
data class VoiceShortcut(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val triggerNormalized: String,      // lower-cased trigger phrase for matching
    val commands: String,               // newline-separated command texts
    val createdAt: Long = System.currentTimeMillis()
)
