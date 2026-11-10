@file:OptIn(ExperimentalNativeApi::class)

package io.karandash.diagnostics

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.Platform

/**
 * Native implementation delegates to platform-specific capture functions
 * because `getrusage` is available on macOS/Linux but not Windows (MinGW).
 */
actual object PlatformDiagnostics {
    actual fun captureSnapshot(elapsedMs: Long): ResourceSnapshot = nativeCaptureSnapshot(elapsedMs)

    actual val buildVariant: String = "native"
    actual val osName: String = nativeOsName
}

/**
 * Platform-specific resource capture. Implemented per native sub-target:
 * - macOS: `getrusage` for peak RSS and CPU, Mach `task_info` for current RSS
 * - Linux: `/proc/self/status` for memory/threads, `/proc/self/stat` for CPU
 * - MinGW: `GetProcessTimes` for CPU (memory stays 0)
 */
internal expect fun nativeCaptureSnapshot(elapsedMs: Long): ResourceSnapshot

/** Short OS name of this native target: `"macos"`, `"linux"` or `"windows"`. */
internal expect val nativeOsFamily: String

/** OS identifier of the running target, e.g. `"macos-arm64"`, `"linux-x64"`, `"windows-x64"`. */
internal val nativeOsName: String = "$nativeOsFamily-${nativeArch()}"

private fun nativeArch(): String =
    when (Platform.cpuArchitecture) {
        CpuArchitecture.ARM64 -> "arm64"
        CpuArchitecture.X64 -> "x64"
        else -> Platform.cpuArchitecture.name.lowercase()
    }
