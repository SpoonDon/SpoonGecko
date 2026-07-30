package com.spoongecko.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryEntry(val id: Long, val url: String, val title: String, val timestamp: Long, val visitCount: Int)
data class BookmarkEntry(val id: Long, val url: String, val title: String, val timestamp: Long)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "spoon_gecko.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT DEFAULT '', timestamp INTEGER NOT NULL, visit_count INTEGER DEFAULT 1)")
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT DEFAULT '', timestamp INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS history")
        db.execSQL("DROP TABLE IF EXISTS bookmarks")
        onCreate(db)
    }

    fun addHistory(url: String, title: String) {
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT id, visit_count FROM history WHERE url = ?", arrayOf(url))
        if (cursor.moveToFirst()) {
            val values = ContentValues().apply {
                put("title", title)
                put("timestamp", System.currentTimeMillis())
                put("visit_count", cursor.getInt(1) + 1)
            }
            db.update("history", values, "id = ?", arrayOf(cursor.getLong(0).toString()))
        } else {
            db.insert("history", null, ContentValues().apply {
                put("url", url); put("title", title)
                put("timestamp", System.currentTimeMillis()); put("visit_count", 1)
            })
        }
        cursor.close()
    }

    fun getHistory(search: String = "", sortBy: String = "timestamp DESC"): List<HistoryEntry> {
        val db = readableDatabase
        val query = if (search.isEmpty()) "SELECT * FROM history ORDER BY $sortBy"
                    else "SELECT * FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY $sortBy"
        val args = if (search.isEmpty()) null else arrayOf("%$search%", "%$search%")
        val cursor = db.rawQuery(query, args)
        val list = mutableListOf<HistoryEntry>()
        while (cursor.moveToNext()) {
            list.add(HistoryEntry(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4)))
        }
        cursor.close()
        return list
    }

    fun deleteHistory(id: Long) { writableDatabase.delete("history", "id = ?", arrayOf(id.toString())) }
    fun deleteAllHistory() { writableDatabase.delete("history", null, null) }

    fun addBookmark(url: String, title: String): Boolean {
        return try {
            writableDatabase.insertOrThrow("bookmarks", null, ContentValues().apply {
                put("url", url); put("title", title); put("timestamp", System.currentTimeMillis())
            })
            true
        } catch (e: Exception) { false }
    }

    fun getBookmarks(sortBy: String = "timestamp DESC"): List<BookmarkEntry> {
        val cursor = readableDatabase.rawQuery("SELECT * FROM bookmarks ORDER BY $sortBy", null)
        val list = mutableListOf<BookmarkEntry>()
        while (cursor.moveToNext()) {
            list.add(BookmarkEntry(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
        }
        cursor.close()
        return list
    }

    fun isBookmarked(url: String): Boolean {
        val cursor = readableDatabase.rawQuery("SELECT id FROM bookmarks WHERE url = ?", arrayOf(url))
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }

    fun updateBookmark(id: Long, title: String, url: String) {
        writableDatabase.update("bookmarks", ContentValues().apply { put("title", title); put("url", url) }, "id = ?", arrayOf(id.toString()))
    }

    fun deleteBookmark(id: Long) { writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString())) }

fun getSuggestions(query: String): List<String> {
    val db = readableDatabase
    val suggestions = mutableListOf<String>()
    if (query.isBlank()) return suggestions
    
    val searchQuery = "%$query%"
    
    // Fetch from History (ordered by most visited)
    val cursorHistory = db.rawQuery(
        "SELECT url FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY visit_count DESC, timestamp DESC LIMIT 5", 
        arrayOf(searchQuery, searchQuery)
    )
    while (cursorHistory.moveToNext()) {
        suggestions.add(cursorHistory.getString(0))
    }
    cursorHistory.close()
    
    // Fetch from Bookmarks
    val cursorBookmarks = db.rawQuery(
        "SELECT url FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY timestamp DESC LIMIT 5", 
        arrayOf(searchQuery, searchQuery)
    )
    while (cursorBookmarks.moveToNext()) {
        val url = cursorBookmarks.getString(0)
        if (!suggestions.contains(url)) {
            suggestions.add(url)
        }
    }
    cursorBookmarks.close()
    
    return suggestions
}
}
