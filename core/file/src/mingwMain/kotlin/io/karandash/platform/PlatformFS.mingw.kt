
package io.karandash.platform

import kotlinx.cinterop.*
import platform.posix.*

private const val GETCWD_BUFFER_SIZE = 4096

@OptIn(ExperimentalForeignApi::class)
actual fun getCurrentWorkingDirectoryString(): String {
    memScoped {
        val buffer = allocArray<ByteVar>(GETCWD_BUFFER_SIZE)
        _getcwd(buffer, GETCWD_BUFFER_SIZE) ?: error("getcwd failed")
        return buffer.toKString()
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun getUserHomeDirectoryString(): String {
    val userProfile = getenv("USERPROFILE")?.toKString()?.takeIf { it.isNotBlank() }
    if (userProfile != null) return userProfile

    val home = getenv("HOME")?.toKString()?.takeIf { it.isNotBlank() }
    if (home != null) return home

    return getCurrentWorkingDirectoryString()
}

@OptIn(ExperimentalForeignApi::class)
actual fun fallbackTempDirectoryString(): String =
    getenv("TEMP")?.toKString()?.takeIf { it.isNotBlank() }
        ?: getenv("TMP")?.toKString()?.takeIf { it.isNotBlank() }
        ?: "C:\\Windows\\Temp"
