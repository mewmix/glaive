package com.mewmix.glaive.data

import androidx.annotation.Keep

@Keep // Protect from ProGuard
data class GlaiveItem(
    val name: String,
    val path: String,
    val type: Int, // 1=Dir, 2=Img, 3=Vid, 4=Apk, 5=Doc
    val size: Long,
    val mtime: Long
) {
    companion object {
        const val TYPE_DIR = 4 // DT_DIR
        const val TYPE_FILE = 8 // DT_REG
        const val TYPE_IMG = 2
        const val TYPE_VID = 3
        const val TYPE_APK = 100
        const val TYPE_DOC = 5
    }
}
