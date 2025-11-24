package com.mewmix.glaive.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FileOperations {

    suspend fun copy(source: File, destDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (source.isDirectory) {
                source.copyRecursively(File(destDir, source.name), overwrite = true)
            } else {
                source.copyTo(File(destDir, source.name), overwrite = true)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun move(source: File, destDir: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val destFile = File(destDir, source.name)
            // Try atomic move first
            if (source.renameTo(destFile)) return@withContext true
            
            // Fallback: Copy then Delete
            if (copy(source, destDir)) {
                delete(source)
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun delete(target: File): Boolean = withContext(Dispatchers.IO) {
        try {
            target.deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun createFile(parent: File, name: String, content: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(parent, name)
            if (file.exists()) return@withContext false
            file.writeText(content)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun createDir(parent: File, name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(parent, name)
            if (dir.exists()) return@withContext false
            dir.mkdirs()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
