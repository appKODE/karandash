package io.karandash.cache.cas

import io.karandash.testfixtures.TestFiles
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

class CacheIndexStoreTest {
    private lateinit var tempDir: Path
    private lateinit var store: CacheIndexStore

    @BeforeTest
    fun setUp() {
        tempDir = TestFiles.createTestTempDirectory("karandash-index-test")
        SystemFileSystem.createDirectories(tempDir)
        store = CacheIndexStore(indexDir = tempDir)
    }

    @AfterTest
    fun tearDown() {
        TestFiles.deleteRecursively(tempDir)
    }

    private fun entry(
        digest: String = "a".repeat(64),
        ttlMs: Long = 60_000L
    ): CacheIndexEntry {
        val now = 1_000_000L
        return CacheIndexEntry(
            digest = digest,
            expiresAt = now + ttlMs,
            createdAt = now,
        )
    }

    @Test
    fun readReturnsNullForMissingEntry() {
        assertNull(store.read("ns", "missing-key"))
    }

    @Test
    fun writeAndReadRoundTrips() {
        val original = entry()
        store.write("ns", "key1", original)
        val retrieved = store.read("ns", "key1")
        assertNotNull(retrieved)
        assertEquals(original.digest, retrieved.digest)
        assertEquals(original.expiresAt, retrieved.expiresAt)
        assertEquals(original.createdAt, retrieved.createdAt)
    }

    @Test
    fun deleteRemovesEntry() {
        store.write("ns", "key2", entry())
        assertTrue(store.delete("ns", "key2"))
        assertNull(store.read("ns", "key2"))
    }

    @Test
    fun deleteReturnsFalseForMissingEntry() {
        assertFalse(store.delete("ns", "no-such-key"))
    }

    @Test
    fun listKeysReturnsKeysInNamespace() {
        store.write("ns", "k1", entry())
        store.write("ns", "k2", entry())
        store.write("other", "k3", entry())

        val keys = store.listKeys("ns")
        assertEquals(2, keys.size)
        assertTrue(keys.contains("k1"))
        assertTrue(keys.contains("k2"))
    }

    @Test
    fun listKeysReturnsEmptyForMissingNamespace() {
        assertEquals(emptyList(), store.listKeys("no-such-namespace"))
    }

    @Test
    fun writeOverwritesExistingEntry() {
        store.write("ns", "key", entry(digest = "a".repeat(64)))
        store.write("ns", "key", entry(digest = "b".repeat(64)))
        val retrieved = store.read("ns", "key")
        assertNotNull(retrieved)
        assertEquals("b".repeat(64), retrieved.digest)
    }

    @Test
    fun indexPathRejectsPathTraversalInKey() {
        assertFailsWith<IllegalArgumentException> {
            store.indexPath("ns", "../etc/passwd")
        }
    }

    @Test
    fun indexPathRejectsPathTraversalInNamespace() {
        assertFailsWith<IllegalArgumentException> {
            store.indexPath("../evil", "key")
        }
    }

    @Test
    fun indexPathAllowsSlashInKey() {
        // HTTP URL cache keys contain slashes - must not be rejected
        store.indexPath("ns", "GET:https://example.com/api") // should not throw
    }
}
