package io.karandash.cache.cas

import io.karandash.testfixtures.TestFiles
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CasBlobStoreTest {
    private lateinit var tempDir: Path
    private lateinit var store: CasBlobStore

    @BeforeTest
    fun setUp() {
        tempDir = TestFiles.createTestTempDirectory("karandash-cas-blob-test")
        SystemFileSystem.createDirectories(tempDir)
        store = CasBlobStore(casDir = tempDir)
    }

    @AfterTest
    fun tearDown() {
        TestFiles.deleteRecursively(tempDir)
    }

    @Test
    fun putRethrowsWhenTheShardDirectoryCannotBeCreated() {
        val file = Path(tempDir, "not-a-dir")
        SystemFileSystem.sink(file).close()
        val blocked = CasBlobStore(casDir = Path(file, "cas"))

        // the mkdir error itself, not the later "Failed to open .../.<blob>.tmp" from the write
        val e = assertFailsWith<IOException> { blocked.put("x".encodeToByteArray()) }
        assertFalse(e.message.orEmpty().contains(".tmp"), e.message)
    }

    @Test
    fun putReturnsCorrectDigest() {
        val content = "hello world".encodeToByteArray()
        val digest = store.put(content)
        val expected = CasDigest.of(content)
        assertEquals(expected.hex, digest.hex)
    }

    @Test
    fun putIsIdempotent() {
        val content = "same content".encodeToByteArray()
        val digest1 = store.put(content)
        val digest2 = store.put(content)
        assertEquals(digest1.hex, digest2.hex)
    }

    @Test
    fun getReturnsNullForUnknownDigest() {
        val fakeDigest = CasDigest("0".repeat(64))
        assertNull(store.get(fakeDigest))
    }

    @Test
    fun getReturnsContentAfterPut() {
        val content = "test blob content".encodeToByteArray()
        val digest = store.put(content)
        val retrieved = store.get(digest)
        assertNotNull(retrieved)
        assertEquals(content.decodeToString(), retrieved.decodeToString())
    }

    @Test
    fun deleteRemovesBlobAndGetReturnsNull() {
        val content = "to be deleted".encodeToByteArray()
        val digest = store.put(content)
        assertTrue(store.exists(digest))
        assertTrue(store.delete(digest))
        assertNull(store.get(digest))
        assertFalse(store.exists(digest))
    }

    @Test
    fun deleteReturnsFalseForMissingBlob() {
        val fakeDigest = CasDigest("1".repeat(64))
        assertFalse(store.delete(fakeDigest))
    }
}
