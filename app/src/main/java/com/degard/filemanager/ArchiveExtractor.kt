package com.degard.filemanager

import android.util.Log
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ArchiveExtractor {

    private const val TAG = "FileManager"

    fun isZip(file: File) = file.name.endsWith(".zip", ignoreCase = true) ||
        file.name.endsWith(".apk", ignoreCase = true) ||
        file.name.endsWith(".epub", ignoreCase = true) ||
        file.name.endsWith(".cbr", ignoreCase = true)

    /**
     * Extract a ZIP archive into the given target directory.
     * Returns null on success, or an error message on failure.
     */
    fun extractZip(archive: File, target: File): String? {
        if (!archive.exists()) return "Archive not found"
        if (!target.exists() && !target.mkdirs()) return "Cannot create target folder"
        return try {
            ZipArchiveInputStream(BufferedInputStream(FileInputStream(archive)), null, true).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val outPath = safeResolve(target, entry.name) ?: continue
                    if (entry.isDirectory) {
                        outPath.mkdirs()
                    } else {
                        outPath.parentFile?.mkdirs()
                        FileOutputStream(outPath).use { out ->
                            input.copyTo(out, 64 * 1024)
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "ZIP extraction failed", e)
            "ZIP error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun safeResolve(base: File, name: String): File? {
        val clean = name.replace('\\', '/')
        val parts = clean.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        return parts.fold(base) { acc, part ->
            if (part == "..") acc else File(acc, part)
        }
    }
}
