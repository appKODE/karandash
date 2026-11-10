@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.diagnostics

import io.karandash.diagnostics.psapi.karandashProcessMemory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.FILETIME
import platform.windows.GetCurrentProcess
import platform.windows.GetProcessTimes

internal actual val nativeOsFamily: String = "windows"

private const val HUNDRED_NS_PER_MS = 10_000L
private const val FILETIME_HIGH_SHIFT = 32

/**
 * Windows/MinGW implementation: `GetProcessTimes` for user and kernel CPU time (100 ns units) and psapi's
 * `GetProcessMemoryInfo` (the `psapi` cinterop of this module) for the peak and current working set in bytes.
 * Both fall back to 0 when the call fails.
 */
internal actual fun nativeCaptureSnapshot(elapsedMs: Long): ResourceSnapshot =
    memScoped {
        val creation = alloc<FILETIME>()
        val exit = alloc<FILETIME>()
        val kernel = alloc<FILETIME>()
        val user = alloc<FILETIME>()
        val ok = GetProcessTimes(GetCurrentProcess(), creation.ptr, exit.ptr, kernel.ptr, user.ptr) != 0
        val peak = alloc<ULongVar>()
        val current = alloc<ULongVar>()
        val haveMemory = karandashProcessMemory(peak.ptr, current.ptr) != 0
        ResourceSnapshot(
            timestampMs = elapsedMs,
            peakRssBytes = if (haveMemory) peak.value.toLong() else 0,
            currentRssBytes = if (haveMemory) current.value.toLong() else 0,
            cpuUserTimeMs = if (ok) user.toTicks() / HUNDRED_NS_PER_MS else 0,
            cpuSystemTimeMs = if (ok) kernel.toTicks() / HUNDRED_NS_PER_MS else 0,
        )
    }

private fun FILETIME.toTicks(): Long = (dwHighDateTime.toLong() shl FILETIME_HIGH_SHIFT) or dwLowDateTime.toLong()
