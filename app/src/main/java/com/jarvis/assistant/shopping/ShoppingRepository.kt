package com.jarvis.assistant.shopping

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShoppingRepository(private val dao: ShoppingDao) {

    suspend fun addItem(text: String) = withContext(Dispatchers.IO) {
        dao.insert(ShoppingItem(item = text.trim()))
    }

    suspend fun getItems(): List<ShoppingItem> = withContext(Dispatchers.IO) {
        dao.getPending()
    }

    suspend fun markDone(item: String) = withContext(Dispatchers.IO) {
        dao.complete(item)
    }

    suspend fun clearDone() = withContext(Dispatchers.IO) {
        dao.clearCompleted()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }
}
