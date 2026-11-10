@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.karandash.platform

import kotlinx.cinterop.COpaquePointer

expect fun openNativeLibrary(path: String): COpaquePointer?

expect fun closeNativeLibrary(handle: COpaquePointer)

expect fun findNativeSymbol(
    handle: COpaquePointer,
    name: String,
): COpaquePointer?

expect fun nativeLibraryError(): String
