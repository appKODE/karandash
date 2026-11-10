@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.karandash.platform

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.toKString
import platform.posix.RTLD_LAZY
import platform.posix.dlclose
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

actual fun openNativeLibrary(path: String): COpaquePointer? = dlopen(path, RTLD_LAZY)

actual fun closeNativeLibrary(handle: COpaquePointer) {
    dlclose(handle)
}

actual fun findNativeSymbol(
    handle: COpaquePointer,
    name: String,
): COpaquePointer? = dlsym(handle, name)

actual fun nativeLibraryError(): String = dlerror()?.toKString() ?: "Unknown dynamic loader error"
