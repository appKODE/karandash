package io.karandash.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class NativeDynamicLibraryTest {
    @Test
    fun openNonexistentLibraryReturnsNull() {
        assertNull(openNativeLibrary("nonexistent_lib_that_does_not_exist.so"))
    }

    @Test
    fun nativeLibraryErrorIsNonBlankAfterFailedOpen() {
        openNativeLibrary("nonexistent_lib_that_does_not_exist.so")
        assertTrue(nativeLibraryError().isNotBlank())
    }
}
