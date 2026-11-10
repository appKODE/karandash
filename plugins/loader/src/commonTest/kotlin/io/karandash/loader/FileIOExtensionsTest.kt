package io.karandash.loader

import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileIOExtensionsTest {
    @Test
    fun testExtension() {
        val path = Path("test.txt")
        assertEquals("txt", path.extension)

        val pathNoExt = Path("test")
        assertEquals("", pathNoExt.extension)

        val pathMultipleDots = Path("archive.tar.gz")
        assertEquals("gz", pathMultipleDots.extension)
    }

    @Test
    fun testReadWriteBytes() {
        val temp = TestFiles.createTestTempDirectory("file-io-extensions")
        val testFile = Path(temp, "test-file.bin")

        try {
            val testData = byteArrayOf(1, 2, 3, 4, 5)
            testFile.writeBytes(testData)

            assertTrue(testFile.exists(), "File should exist after writing")

            val readData = testFile.readBytes()
            assertContentEquals(testData, readData, "Read data should match written data")
        } finally {
            deleteIfExists(testFile)
        }
    }

    @Test
    fun testCopyTo() {
        val temp = TestFiles.createTestTempDirectory("file-io-extensions")
        val source = Path(temp, "source.txt")
        val dest = Path(temp, "dest.txt")

        try {
            val testData = "Hello, World!".encodeToByteArray()
            source.writeBytes(testData)

            source.copyTo(dest)
            assertTrue(dest.exists(), "Destination should exist after copy")

            val copiedData = dest.readBytes()
            assertContentEquals(testData, copiedData, "Copied data should match source")
        } finally {
            deleteIfExists(source)
            deleteIfExists(dest)
        }
    }

    @Test
    fun testCopyToOverwrite() {
        val temp = TestFiles.createTestTempDirectory("file-io-extensions")
        val source = Path(temp, "source2.txt")
        val dest = Path(temp, "dest2.txt")

        try {
            source.writeBytes("source".encodeToByteArray())
            dest.writeBytes("dest".encodeToByteArray())

            // Should fail without overwrite
            assertFails {
                source.copyTo(dest, overwrite = false)
            }

            // Should succeed with overwrite
            source.copyTo(dest, overwrite = true)
            val copiedData = dest.readBytes()
            assertContentEquals("source".encodeToByteArray(), copiedData)
        } finally {
            deleteIfExists(source)
            deleteIfExists(dest)
        }
    }

    @Test
    fun testDeleteIfExists() {
        val temp = TestFiles.createTestTempDirectory("file-io-extensions")
        val testFile = Path(temp, "delete-test.txt")

        try {
            // Should return false if file doesn't exist
            assertFalse(deleteIfExists(testFile), "Should return false for non-existent file")

            // Create file and verify deletion
            testFile.writeBytes("test".encodeToByteArray())
            assertTrue(testFile.exists())

            assertTrue(deleteIfExists(testFile), "Should return true when deleting existing file")
            assertFalse(testFile.exists(), "File should not exist after deletion")
        } finally {
            deleteIfExists(testFile)
        }
    }

    @Test
    fun testCreateDirectories() {
        val temp = TestFiles.createTestTempDirectory("file-io-extensions")
        val nestedDir = Path(temp, "nested/deep/directory")

        try {
            assertTrue(createDirectories(nestedDir), "Should return true when creating new directories")
            assertTrue(nestedDir.exists())
            assertTrue(nestedDir.isDirectory())

            assertFalse(createDirectories(nestedDir), "Should return false when directory already exists")
        } finally {
            // Cleanup
            deleteIfExists(nestedDir)
            deleteIfExists(Path(temp, "nested/deep"))
            deleteIfExists(Path(temp, "nested"))
        }
    }

    @Test
    fun testFileSize() {
        val temp = TestFiles.createTestTempDirectory("file-io-extensions")
        val testFile = Path(temp, "size-test.bin")

        try {
            val testData = ByteArray(1024) { it.toByte() }
            testFile.writeBytes(testData)

            assertEquals(1024L, testFile.fileSize())
        } finally {
            deleteIfExists(testFile)
        }
    }

    @Test
    fun testMoveFile() {
        val temp = TestFiles.createTestTempDirectory("file-io-extensions")
        val source = Path(temp, "move-source.txt")
        val dest = Path(temp, "move-dest.txt")

        try {
            val testData = "move test".encodeToByteArray()
            source.writeBytes(testData)

            moveFile(source, dest)

            assertFalse(source.exists(), "Source should not exist after move")
            assertTrue(dest.exists(), "Destination should exist after move")
            assertContentEquals(testData, dest.readBytes(), "Data should be preserved after move")
        } finally {
            deleteIfExists(source)
            deleteIfExists(dest)
        }
    }
}
