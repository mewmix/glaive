package com.mewmix.glaive.core

import com.mewmix.glaive.data.GlaiveItem
import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlin.math.max

/**
 * Cursor that decodes entries directly from the shared native buffer without creating
 * intermediate arrays or duplicating memory.
 */
class GlaiveCursor(
    private val buffer: ByteBuffer,
    private val parentPath: String,
    private val limit: Int
) {
    private var offset = 0
    private val charset: Charset = Charsets.UTF_8

    val approxCount: Int = max(1, limit / 48)

    fun hasNext(): Boolean = offset < limit

    fun next(reuseItem: GlaiveItem? = null): GlaiveItem {
        if (!hasNext()) throw IllegalStateException("Cursor overflow")

        val type = buffer.get(offset).toInt() and 0xFF
        val nameLen = buffer.get(offset + 1).toInt() and 0xFF

        val nameBytes = ByteArray(nameLen)
        val nameStart = offset + 2
        for (i in 0 until nameLen) {
            nameBytes[i] = buffer.get(nameStart + i)
        }
        val sizePos = nameStart + nameLen
        val size = buffer.getLong(sizePos)
        val time = buffer.getLong(sizePos + 8)

        offset += (2 + nameLen + 16)

        val name = String(nameBytes, charset)
        val path = if (parentPath.endsWith("/")) {
            parentPath + name
        } else {
            "$parentPath/$name"
        }

        return reuseItem?.apply {
            this.name = name
            this.path = path
            this.type = type
            this.size = size
            this.mtime = time
        } ?: GlaiveItem(
            name = name,
            path = path,
            type = type,
            size = size,
            mtime = time
        )
    }
}
