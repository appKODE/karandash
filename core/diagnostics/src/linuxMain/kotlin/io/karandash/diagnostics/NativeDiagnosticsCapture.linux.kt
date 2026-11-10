@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.diagnostics

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix._SC_CLK_TCK
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.sysconf

internal actual val nativeOsFamily: String = "linux"

private const val BYTES_PER_KB = 1024L
private const val MS_PER_SECOND = 1_000L
private const val PROC_STATUS_PATH = "/proc/self/status"
private const val PROC_STAT_PATH = "/proc/self/stat"
private const val PROC_FD_DIR = "/proc/self/fd"
private const val LINE_BUFFER_SIZE = 4096
private const val DOT_ENTRIES = 2

/**
 * Linux implementation using `/proc/self/status` for memory and thread count,
 * `/proc/self/stat` for per-mode CPU time, and `/proc/self/fd` for open FDs.
 *
 * `getrusage` is not available in Kotlin/Native's `platform.posix` on Linux,
 * so we parse procfs instead.
 */
internal actual fun nativeCaptureSnapshot(elapsedMs: Long): ResourceSnapshot {
    val peakRssKb = readProcStatusField("VmHWM")
    val currentRssKb = readProcStatusField("VmRSS")
    val threadCount = readProcStatusField("Threads").toInt()
    val openFds = countOpenFds()
    val cpuTimes = readProcStatCpuTimes()
    return ResourceSnapshot(
        timestampMs = elapsedMs,
        peakRssBytes = peakRssKb * BYTES_PER_KB,
        currentRssBytes = currentRssKb * BYTES_PER_KB,
        cpuUserTimeMs = cpuTimes.first,
        cpuSystemTimeMs = cpuTimes.second,
        threadCount = threadCount,
        openFds = openFds,
    )
}

/**
 * Parse a numeric field from `/proc/self/status`.
 * Lines have the format: `FieldName:\t  12345 kB`
 * Returns the numeric value in kB (for memory) or raw count, or 0 if not found.
 */
private fun readProcStatusField(fieldName: String): Long {
    val file = fopen(PROC_STATUS_PATH, "r") ?: return 0
    val buf = ByteArray(LINE_BUFFER_SIZE)
    val prefix = "$fieldName:"
    var result = 0L
    try {
        while (fgets(buf.refTo(0), LINE_BUFFER_SIZE, file) != null) {
            val line = buf.toKString()
            if (line.startsWith(prefix)) {
                result = line
                    .substringAfter(prefix)
                    .trim()
                    .substringBefore(" ")
                    .toLongOrNull() ?: 0
                break
            }
        }
    } finally {
        fclose(file)
    }
    return result
}

/**
 * Read user and system CPU time from `/proc/self/stat`.
 *
 * The file has a single line with space-separated fields. Field 2 (comm) is
 * enclosed in parentheses and may contain spaces. After splitting past the
 * closing paren, fields 14 (utime) and 15 (stime) are in clock ticks.
 *
 * Returns (userMs, systemMs).
 */
private fun readProcStatCpuTimes(): Pair<Long, Long> {
    val file = fopen(PROC_STAT_PATH, "r") ?: return Pair(0L, 0L)
    val buf = ByteArray(LINE_BUFFER_SIZE)
    try {
        if (fgets(buf.refTo(0), LINE_BUFFER_SIZE, file) == null) return Pair(0L, 0L)
        val line = buf.toKString()
        // Skip past "(comm) " - field indices after comm start at 0 = state
        val afterComm = line.substringAfter(") ", "")
        if (afterComm.isEmpty()) return Pair(0L, 0L)
        val fields = afterComm.split(' ')
        // utime is at original 1-based index 14 → 0-based after pid+comm = index 11
        // stime is at original 1-based index 15 → 0-based after pid+comm = index 12
        val utimeIdx = 11
        val stimeIdx = 12
        if (fields.size <= stimeIdx) return Pair(0L, 0L)
        // Use sysconf(_SC_CLK_TCK) for the actual kernel clock tick rate (typically 100).
        // CLOCKS_PER_SEC is the POSIX constant 1_000_000, which causes integer truncation
        // for short-lived processes.
        val clocksPerSec = sysconf(_SC_CLK_TCK)
        if (clocksPerSec <= 0) return Pair(0L, 0L)
        val utimeTicks = fields[utimeIdx].toLongOrNull() ?: 0
        val stimeTicks = fields[stimeIdx].toLongOrNull() ?: 0
        val tickRate = clocksPerSec.toLong()
        return Pair(
            utimeTicks * MS_PER_SECOND / tickRate,
            stimeTicks * MS_PER_SECOND / tickRate,
        )
    } finally {
        fclose(file)
    }
}

/**
 * Count entries in `/proc/self/fd` to determine the number of open file descriptors.
 * Returns 0 if the directory cannot be read.
 */
private fun countOpenFds(): Int {
    val dir = opendir(PROC_FD_DIR) ?: return 0
    var count = 0
    try {
        while (readdir(dir) != null) {
            count++
        }
        // Subtract 2 for "." and ".."
        return (count - DOT_ENTRIES).coerceAtLeast(0)
    } finally {
        closedir(dir)
    }
}
