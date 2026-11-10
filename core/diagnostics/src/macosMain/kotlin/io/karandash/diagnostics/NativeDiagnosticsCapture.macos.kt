@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.diagnostics

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_basic_info
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage

/**
 * macOS implementation using `getrusage(RUSAGE_SELF)`.
 * On macOS, `ru_maxrss` is already in **bytes**.
 *
 * `currentRssBytes` comes from Mach `task_info(MACH_TASK_BASIC_INFO)`; it falls back to
 * the peak when the call fails.
 */
internal actual fun nativeCaptureSnapshot(elapsedMs: Long): ResourceSnapshot {
    memScoped {
        val usage = alloc<rusage>()
        getrusage(RUSAGE_SELF, usage.ptr)

        return ResourceSnapshot(
            timestampMs = elapsedMs,
            peakRssBytes = usage.ru_maxrss,
            currentRssBytes = currentRssBytes() ?: usage.ru_maxrss,
            cpuUserTimeMs = timevalToMs(usage.ru_utime),
            cpuSystemTimeMs = timevalToMs(usage.ru_stime),
        )
    }
}

private fun currentRssBytes(): Long? =
    memScoped {
        val info = alloc<mach_task_basic_info>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<mach_task_basic_info>() / sizeOf<platform.posix.int32_tVar>()).convert()
        val status = task_info(mach_task_self_, MACH_TASK_BASIC_INFO.convert(), info.ptr.reinterpret(), count.ptr)
        if (status == 0) info.resident_size.toLong() else null
    }

private const val MACH_TASK_BASIC_INFO = 20

private fun timevalToMs(tv: platform.posix.timeval): Long = tv.tv_sec * MS_PER_SECOND + tv.tv_usec / USEC_PER_MS

internal actual val nativeOsFamily: String = "macos"

private const val MS_PER_SECOND = 1_000L
private const val USEC_PER_MS = 1_000L
