package io.github.iamr0s.carvex.sessioncore

import java.io.File
import java.io.RandomAccessFile
import java.time.Instant

/** Export raw diagnostics, independently of the UI's shorter, formatted preview. */
internal object DiagnosticLogText {
    const val MEMORY_LINES = 2000
    const val STARTUP_LINES = 24
    // The writer rotates at 1 MiB; allow the final log record that crossed that limit.
    const val DISK_BYTES = 1024 * 1024 + 4096

    fun retain(lines: List<String>): List<String> =
        if (lines.size <= MEMORY_LINES) lines
        else lines.take(STARTUP_LINES) + lines.takeLast(MEMORY_LINES - STARTUP_LINES)

    /** Keep complete UTF-8 lines, with bounded allocation even for an old oversized file. */
    fun readDisk(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val start = (length - DISK_BYTES).coerceAtLeast(0)
            input.seek(start)
            val bytes = ByteArray((length - start).toInt())
            input.readFully(bytes)
            val text = bytes.toString(Charsets.UTF_8)
            val complete = if (start == 0L) text else text.substringAfter('\n', "")
            complete.lineSequence().filter { it.isNotEmpty() }.toList()
        }
    }

    /** A pending clear must not reintroduce the previous disk generation. */
    fun merge(generation: Long, diskGeneration: Long, disk: List<String>,
              evidence: List<String>, recent: List<String>): List<String> {
        val persisted = if (generation == diskGeneration) disk else emptyList()
        return (persisted + evidence + recent).distinct().sortedBy {
            runCatching { Instant.parse(it.substringBefore(" [")) }.getOrDefault(Instant.MIN)
        }
    }
}
