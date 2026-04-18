package com.jarvis.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.memory.db.JarvisDatabase
import com.jarvis.assistant.memory.db.entity.ConversationTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = JarvisDatabase.getInstance(app).conversationDao()

    private val _turns = MutableStateFlow<List<ConversationTurn>>(emptyList())
    val turns: StateFlow<List<ConversationTurn>> = _turns.asStateFlow()

    private var polling = false

    fun startPolling() {
        if (polling) return
        polling = true
        viewModelScope.launch(Dispatchers.IO) {
            while (polling) {
                _turns.value = dao.getRecentTurns(60)
                delay(1_500)
            }
        }
    }

    fun stopPolling() {
        polling = false
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _turns.value = dao.getRecentTurns(60)
        }
    }

    override fun onCleared() {
        super.onCleared()
        polling = false
    }
}
