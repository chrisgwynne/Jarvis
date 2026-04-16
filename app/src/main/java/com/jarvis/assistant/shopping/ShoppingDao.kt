package com.jarvis.assistant.shopping

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ShoppingDao {

    @Insert
    fun insert(item: ShoppingItem)

    @Query("SELECT * FROM shopping_items WHERE completed = 0 ORDER BY addedAt ASC")
    fun getPending(): List<ShoppingItem>

    @Query("UPDATE shopping_items SET completed = 1 WHERE LOWER(item) = LOWER(:item)")
    fun complete(item: String): Int

    @Query("DELETE FROM shopping_items WHERE completed = 1")
    fun clearCompleted()

    @Query("DELETE FROM shopping_items")
    fun clearAll()
}
