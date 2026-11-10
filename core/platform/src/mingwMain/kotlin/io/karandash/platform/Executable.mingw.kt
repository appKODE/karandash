package io.karandash.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.windows.GetModuleFileNameW
import platform.windows.MAX_PATH
import platform.windows.WCHARVar

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentExecutable(): String? =
    memScoped {
        val buffer = allocArray<WCHARVar>(MAX_PATH)
        val length = GetModuleFileNameW(null, buffer, MAX_PATH.toUInt())
        if (length == 0u) null else buffer.toKString()
    }
