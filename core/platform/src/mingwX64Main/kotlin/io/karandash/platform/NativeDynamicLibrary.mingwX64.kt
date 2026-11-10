@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.karandash.platform

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import platform.windows.FreeLibrary
import platform.windows.GetLastError
import platform.windows.GetProcAddress
import platform.windows.LoadLibraryA

actual fun openNativeLibrary(path: String): COpaquePointer? = LoadLibraryA(path)?.reinterpret()

actual fun closeNativeLibrary(handle: COpaquePointer) {
    FreeLibrary(handle.reinterpret())
}

actual fun findNativeSymbol(
    handle: COpaquePointer,
    name: String,
): COpaquePointer? = GetProcAddress(handle.reinterpret(), name)?.reinterpret()

actual fun nativeLibraryError(): String = "Windows loader error code ${GetLastError()}"
