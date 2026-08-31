package com.degard.filemanager

import android.os.Environment
import android.os.StatFs
import android.app.ActivityManager
import android.content.Context

data class StorageStats(
    val total: Long,
    val used: Long,
    val free: Long
)

object StorageInfo {

    fun storageStats(): StorageStats? {
        val dir = Environment.getDataDirectory()
        val stat = try {
            StatFs(dir.path)
        } catch (e: Exception) {
            return null
        }
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        val total = totalBlocks * blockSize
        val free = availableBlocks * blockSize
        return StorageStats(total, total - free, free)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024.0
            i++
        }
        return if (i == 0) "${bytes} B" else String.format("%.1f %s", value, units[i])
    }

    fun ramInfo(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem
        val avail = mi.availMem
        return "RAM ${formatSize(avail)} / ${formatSize(total)} free"
    }
}
