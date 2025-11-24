package com.mewmix.glaive.core

import android.content.Context
import android.content.SharedPreferences
import com.mewmix.glaive.data.GlaiveItem
import java.io.File

object RecentFilesManager {
    private const val PREF_NAME = "glaive_recents"
    private const val KEY_RECENTS = "recent_paths"
    private const val MAX_RECENTS = 20

    fun addRecent(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val recents = getRecentsPaths(prefs).toMutableList()
        
        recents.remove(path)
        recents.add(0, path)
        
        if (recents.size > MAX_RECENTS) {
            recents.removeAt(recents.lastIndex)
        }
        
        prefs.edit().putStringSet(KEY_RECENTS, recents.toSet()).apply()
        // Note: Set doesn't preserve order, so we might need a better serialization if order matters strictly.
        // For simplicity in MVP, we'll use a comma-separated string instead.
        saveRecentsOrdered(prefs, recents)
    }

    fun getRecents(context: Context): List<GlaiveItem> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val paths = getRecentsOrdered(prefs)
        
        return paths.mapNotNull { path ->
            val file = File(path)
            if (file.exists()) {
                GlaiveItem(
                    name = file.name,
                    path = file.absolutePath,
                    type = if (file.isDirectory) GlaiveItem.TYPE_DIR else GlaiveItem.TYPE_FILE, // Simplified type
                    size = file.length(),
                    mtime = file.lastModified()
                )
            } else {
                null
            }
        }
    }

    private fun saveRecentsOrdered(prefs: SharedPreferences, paths: List<String>) {
        prefs.edit().putString("recent_paths_ordered", paths.joinToString("|")).apply()
    }

    private fun getRecentsOrdered(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString("recent_paths_ordered", "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|")
    }

    private fun getRecentsPaths(prefs: SharedPreferences): Set<String> {
        return prefs.getStringSet(KEY_RECENTS, emptySet()) ?: emptySet()
    }
}
