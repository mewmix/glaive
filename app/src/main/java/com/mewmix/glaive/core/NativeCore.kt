package com.mewmix.glaive.core

import com.mewmix.glaive.data.GlaiveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

object NativeCore {
    init {
        System.loadLibrary("glaive_core")
    }

    private val sharedBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(4 * 1024 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private val bufferLock = Any()

    private external fun nativeFillBuffer(path: String, buffer: ByteBuffer, capacity: Int): Int
    private external fun nativeSearch(root: String, query: String): Array<GlaiveItem>?

    suspend fun list(currentPath: String): List<GlaiveItem> = withContext(Dispatchers.IO) {
        synchronized(bufferLock) {
            val filledBytes = nativeFillBuffer(currentPath, sharedBuffer, sharedBuffer.capacity())
            if (filledBytes <= 0) {
                emptyList()
            } else {
                val cursor = GlaiveCursor(sharedBuffer, currentPath, filledBytes)
                val list = ArrayList<GlaiveItem>(cursor.approxCount)
                while (cursor.hasNext()) {
                    list.add(cursor.next())
                }
                list
            }
        }
    }

    fun listFast(path: String): GlaiveCursor {
        val filledBytes = synchronized(bufferLock) {
            nativeFillBuffer(path, sharedBuffer, sharedBuffer.capacity())
        }
        return GlaiveCursor(sharedBuffer, path, max(0, filledBytes))
    }

    suspend fun search(root: String, query: String): List<GlaiveItem> = withContext(Dispatchers.IO) {
        val results = nativeSearch(root, query)?.filterNotNull() ?: return@withContext emptyList()
        results.onEach { item ->
            if (item.type == GlaiveItem.TYPE_FILE || item.type == GlaiveItem.TYPE_UNKNOWN) {
                item.type = getRefinedType(item.name)
            }
        }
    }

    private fun getRefinedType(name: String): Int {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> GlaiveItem.TYPE_IMG
            "mp4", "mkv", "webm", "avi", "mov", "3gp" -> GlaiveItem.TYPE_VID
            "apk" -> GlaiveItem.TYPE_APK
            "pdf", "doc", "docx", "txt", "md", "xls", "xlsx", "ppt", "pptx" -> GlaiveItem.TYPE_DOC
            else -> GlaiveItem.TYPE_FILE
        }
    }
}
