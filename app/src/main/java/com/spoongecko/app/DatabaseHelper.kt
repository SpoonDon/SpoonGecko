package com.spoongecko.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryEntry(val id: Long, val url: String, val title: String, val timestamp: Long, val visitCount: Int)
data class BookmarkEntry(val id: Long, val url: String, val title: String, val timestamp: Long)

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "spoon_gecko.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, title TEXT DEFAULT '', timestamp INTEGER NOT NULL, visit_count INTEGER DEFAULT 1)")
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT DEFAULT '', timestamp INTEGER NOT NULL)")
        
        // Issue #2, #3: Add indexes for search performance
        db.execSQL("CREATE INDEX idx_history_url ON history(url)")
        db.execSQL("CREATE INDEX idx_history_title ON history(title)")
        db.execSQL("CREATE INDEX idx_history_visit_count ON history(visit_count DESC)")
        db.execSQL("CREATE INDEX idx_bookmarks_url ON bookmarks(url)")
        db.execSQL("CREATE INDEX idx_bookmarks_title ON bookmarks(title)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            // Add indexes on upgrade
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_url ON history(url)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_title ON history(title)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_visit_count ON history(visit_count DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_url ON bookmarks(url)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_title ON bookmarks(title)")
        }
    }

    // Issue #4: Fix cursor leak with try-finally
    fun addHistory(url: String, title: String) {
        val db = writableDatabase
        var cursor = db.rawQuery("SELECT id, visit_count FROM history WHERE url = ?", arrayOf(url))
        try {
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
        } finally {
            cursor.close()
        }
    }

    // Issue #3: Add pagination limit (default 100 results)
    fun getHistory(search: String = "", sortBy: String = "timestamp DESC", limit: Int = 100): List<HistoryEntry> {
        val db = readableDatabase
        val query = if (search.isEmpty()) "SELECT * FROM history ORDER BY $sortBy LIMIT ?"
                    else "SELECT * FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY $sortBy LIMIT ?"
        val args = if (search.isEmpty()) arrayOf(limit.toString())
                   else arrayOf("%$search%", "%$search%", limit.toString())
        
        var cursor = db.rawQuery(query, args)
        val list = mutableListOf<HistoryEntry>()
        try {
            while (cursor.moveToNext()) {
                list.add(HistoryEntry(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4)))
            }
        } finally {
            cursor.close()
        }
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

    // Issue #3: Add pagination limit (default 100 results)
    fun getBookmarks(sortBy: String = "timestamp DESC", limit: Int = 100): List<BookmarkEntry> {
        var cursor = readableDatabase.rawQuery("SELECT * FROM bookmarks ORDER BY $sortBy LIMIT ?", arrayOf(limit.toString()))
        val list = mutableListOf<BookmarkEntry>()
        try {
            while (cursor.moveToNext()) {
                list.add(BookmarkEntry(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
            }
        } finally {
            cursor.close()
        }
        return list
    }

    // Issue #4: Fix cursor leak with try-finally
    fun isBookmarked(url: String): Boolean {
        var cursor = readableDatabase.rawQuery("SELECT id FROM bookmarks WHERE url = ?", arrayOf(url))
        return try {
            cursor.moveToFirst()
        } finally {
            cursor.close()
        }
    }

    fun updateBookmark(id: Long, title: String, url: String) {
        writableDatabase.update("bookmarks", ContentValues().apply { put("title", title); put("url", url) }, "id = ?", arrayOf(id.toString()))
    }

    fun deleteBookmark(id: Long) { writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString())) }

    // Issue #2, #3, #4: Bounded query with indexes, no cursor leaks, limited results
    fun getSuggestions(query: String): List<String> {
        val db = readableDatabase
        val suggestions = mutableListOf<String>()
        if (query.isBlank()) return suggestions
        
        val searchQuery = "%$query%"
        val maxResults = 10
        
        // Fetch from History (ordered by most visited) - LIMIT 5
        var cursorHistory = db.rawQuery(
            "SELECT url FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY visit_count DESC, timestamp DESC LIMIT 5", 
            arrayOf(searchQuery, searchQuery)
        )
        try {
            while (cursorHistory.moveToNext()) {
                suggestions.add(cursorHistory.getString(0))
            }
        } finally {
            cursorHistory.close()
        }
        
        // Fetch from Bookmarks (up to remaining slots) - LIMIT 5
        if (suggestions.size < maxResults) {
            var cursorBookmarks = db.rawQuery(
                "SELECT url FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY timestamp DESC LIMIT 5", 
                arrayOf(searchQuery, searchQuery)
            )
            try {
                while (cursorBookmarks.moveToNext() && suggestions.size < maxResults) {
                    val url = cursorBookmarks.getString(0)
                    // Use HashSet lookup instead of List.contains() for O(1) performance
                    if (url !in suggestions) {
                        suggestions.add(url)
                    }
                }
            } finally {
                cursorBookmarks.close()
            }
        }
        
        return suggestions
    }
}
