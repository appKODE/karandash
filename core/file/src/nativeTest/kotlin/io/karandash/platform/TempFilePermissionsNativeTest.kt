@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.karandash.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.stat
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

/** a temp file is created owner-only, 0600. */
class TempFilePermissionsNativeTest {
    @Test
    fun `temp file is readable and writable by the owner only`() {
        if (Platform.osFamily == OsFamily.WINDOWS) return
        val created = createTempFileInDir(tempDirectory(), "sec15-", ".tmp")
        val mode = memScoped {
            val buf = alloc<stat>()
            check(stat(created.toString(), buf.ptr) == 0) { "stat failed for $created" }
            buf.st_mode.toInt() and PERMISSION_BITS
        }
        deleteIfExists(created)
        assertEquals(OWNER_READ_WRITE, mode, mode.toString(radix = 8))
    }

    private companion object {
        const val PERMISSION_BITS = 511
        const val OWNER_READ_WRITE = 384
    }
}
