package io.karandash.platform

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals

/** a temp file is created owner-only, 0600. */
class TempFilePermissionsJvmTest {
    @Test
    fun `temp file is readable and writable by the owner only`() {
        val dir = java.nio.file.Paths
            .get(TestFiles.createTestTempDirectory("sec15-").toString())
        val created = createTempFileInDir(kotlinx.io.files.Path(dir.toString()), "p-", ".tmp")
        val posix = Files.getFileStore(dir).supportsFileAttributeView("posix")
        if (posix) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(Paths.get(created.toString()))))
        }
        Files.delete(Paths.get(created.toString()))
        Files.delete(dir)
    }
}
