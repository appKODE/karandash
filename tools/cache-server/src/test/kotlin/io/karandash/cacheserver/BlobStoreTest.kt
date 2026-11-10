@file:OptIn(ExperimentalPathApi::class)

package io.karandash.cacheserver

import io.karandash.cache.cas.CasDigest
import io.karandash.logger.NoOpLogger
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlobStoreTest {
    private val root: Path = tempDir("blob-store-test")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun blob(size: Int): Pair<BlobKey, ByteArray> {
        val content = Random.nextBytes(size)
        return BlobKey("team", CasDigest.of(content)) to content
    }

    @Test
    fun `write is atomic and leaves no temp file behind`() =
        runTest {
            val store = BlobStore(root, LIMIT, NoOpLogger)
            val (key, content) = blob(SMALL)
            store.write(key, content)
            assertContentEquals(content, store.read(key))
            assertTrue(store.contains(key))
            assertTrue(root.resolve("tmp").listDirectoryEntries().isEmpty())
            assertEquals(SMALL.toLong(), store.size())
        }

    @Test
    fun `concurrent writes and reads of the same key never see a torn blob`() =
        runTest {
            val store = BlobStore(root, LIMIT, NoOpLogger)
            val (key, content) = blob(LARGE)
            val reads = withContext(Dispatchers.Default) {
                (1..PARALLEL)
                    .map { i -> async { if (i % 2 == 0) store.write(key, content).let { null } else store.read(key) } }
                    .awaitAll()
            }
            reads.filterNotNull().forEach { assertContentEquals(content, it) }
            assertContentEquals(content, store.read(key))
            assertEquals(LARGE.toLong(), store.size())
        }

    @Test
    fun `evicts the least recently used blob over the size limit`() =
        runTest {
            val metrics = CacheMetrics()
            val store = BlobStore(root, SMALL * 3L, NoOpLogger, metrics)
            val (a, aBytes) = blob(SMALL)
            val (b, bBytes) = blob(SMALL)
            val (c, cBytes) = blob(SMALL)
            val (d, dBytes) = blob(SMALL)
            store.write(a, aBytes)
            store.write(b, bBytes)
            store.write(c, cBytes)
            store.read(a)
            store.write(d, dBytes)
            assertNull(store.read(b))
            assertFalse(store.contains(b))
            listOf(a to aBytes, c to cBytes, d to dBytes).forEach { (key, bytes) -> assertContentEquals(bytes, store.read(key)) }
            assertEquals(1L, metrics.evictions.value)
            assertEquals(SMALL * 3L, store.size())
        }

    @Test
    fun `restart reindexes stored blobs and applies a smaller limit`() =
        runTest {
            val first = BlobStore(root, LIMIT, NoOpLogger)
            val (a, aBytes) = blob(SMALL)
            val (b, bBytes) = blob(SMALL)
            first.write(a, aBytes)
            first.write(b, bBytes)
            val restarted = BlobStore(root, SMALL.toLong(), NoOpLogger)
            assertEquals(SMALL.toLong(), restarted.size())
            assertEquals(1, listOf(a, b).count { restarted.contains(it) })
        }

    @Test
    fun `delete removes the blob and reports whether it was stored`() =
        runTest {
            val store = BlobStore(root, LIMIT, NoOpLogger)
            val (key, content) = blob(SMALL)
            store.write(key, content)
            assertTrue(store.delete(key))
            assertFalse(store.delete(key))
            assertNull(store.read(key))
            assertEquals(0L, store.size())
        }

    private companion object {
        const val SMALL = 100
        const val LARGE = 1_000_000
        const val PARALLEL = 64
        const val LIMIT = 100_000_000L
    }
}

/** A test temp directory as a `java.nio` path (the store works on `java.nio`). */
internal fun tempDir(prefix: String): Path = Path.of(TestFiles.createTestTempDirectory(prefix).toString())
