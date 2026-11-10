
package io.karandash.platform

import kotlinx.cinterop.*
import platform.posix.*

private const val GETCWD_BUFFER_SIZE = 4096

@OptIn(ExperimentalForeignApi::class)
actual fun getCurrentWorkingDirectoryString(): String {
    memScoped {
        val buffer = allocArray<ByteVar>(GETCWD_BUFFER_SIZE)
        getcwd(buffer, GETCWD_BUFFER_SIZE.convert()) ?: error("getcwd failed")
        return buffer.toKString()
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun getUserHomeDirectoryString(): String {
    val home = getenv("HOME")?.toKString()?.takeIf { it.isNotBlank() }
    if (home != null) return home

    val userProfile = getenv("USERPROFILE")?.toKString()?.takeIf { it.isNotBlank() }
    if (userProfile != null) return userProfile

    return getCurrentWorkingDirectoryString()
}

actual fun fallbackTempDirectoryString(): String = "/tmp"
