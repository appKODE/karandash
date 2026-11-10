package io.karandash.bootstrap

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform.cpuArchitecture
import kotlin.native.Platform.osFamily

@OptIn(ExperimentalNativeApi::class)
actual fun currentPlatformKey(): String =
    when {
        osFamily == OsFamily.MACOSX && cpuArchitecture == CpuArchitecture.ARM64 -> "macos-arm64"
        osFamily == OsFamily.MACOSX -> "macos-x64"
        osFamily == OsFamily.LINUX -> "linux-x64"
        osFamily == OsFamily.WINDOWS -> "windows-x64"
        else -> "unknown"
    }
