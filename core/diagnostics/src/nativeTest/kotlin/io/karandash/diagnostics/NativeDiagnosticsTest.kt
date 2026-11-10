@file:OptIn(ExperimentalNativeApi::class)

package io.karandash.diagnostics

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertTrue

/** arch per target, current RSS from the kernel rather than the peak. */
class NativeDiagnosticsTest {
    @Test
    fun `os name carries the architecture of the running target`() {
        val arch = when (Platform.cpuArchitecture) {
            kotlin.native.CpuArchitecture.ARM64 -> "arm64"
            kotlin.native.CpuArchitecture.X64 -> "x64"
            else -> error("unexpected architecture ${Platform.cpuArchitecture}")
        }
        assertTrue(PlatformDiagnostics.osName.endsWith("-$arch"), PlatformDiagnostics.osName)
        assertTrue(Regex("(macos|linux|windows)-(arm64|x64)").matches(PlatformDiagnostics.osName), PlatformDiagnostics.osName)
    }

    @Test
    fun `current rss follows live memory not the peak`() {
        if (PlatformDiagnostics.osName.startsWith("windows")) return
        val before = PlatformDiagnostics.captureSnapshot(0)
        val big = List(200) { ByteArray(1_000_000) { 1 } }
        val during = PlatformDiagnostics.captureSnapshot(1)
        assertTrue(big.size == 200)
        assertTrue(during.currentRssBytes > 0 && during.peakRssBytes >= during.currentRssBytes, "$during")
        assertTrue(before.currentRssBytes in 1..before.peakRssBytes, "$before")
    }
}
