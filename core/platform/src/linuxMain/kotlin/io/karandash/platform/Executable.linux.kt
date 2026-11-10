package io.karandash.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.realpath

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentExecutable(): String? =
    memScoped {
        val buffer = allocArray<ByteVar>(PATH_MAX)
        realpath("/proc/self/exe", buffer)?.toKString()
    }
