package io.karandash.cache.remote

import io.karandash.cache.CacheConfig
import io.karandash.cache.CacheManager
import io.karandash.cache.CacheSerializer
import io.karandash.cache.CacheSpec
import io.karandash.cache.CacheTier
import io.karandash.cache.cas.CacheSource
import io.karandash.cache.cas.CasBlobStore
import io.karandash.cache.cas.CasDigest
import io.karandash.logger.NoOpLogger
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CAS caches created by [CacheManager] read from and write to the wired remote layer. */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheManagerRemoteTest {
    private class FakeBackend : RemoteCacheBackend {
        val blobs = mutableMapOf<String, ByteArray>()
        var pushes = 0
        var pulls = 0

        override suspend fun exists(digest: CasDigest): Boolean = digest.hex in blobs

        override suspend fun pull(digest: CasDigest): ByteArray? {
            pulls++
            return blobs[digest.hex]
        }

        override suspend fun push(
            digest: CasDigest,
            content: ByteArray,
        ): Boolean {
            pushes++
            blobs[digest.hex] = content
            return true
        }

        override suspend fun delete(digest: CasDigest) {
            blobs.remove(digest.hex)
        }

        override suspend fun shutdown() = Unit
    }

    private val content = "state-blob".encodeToByteArray()

    private fun manager(
        scope: CoroutineScope,
        backend: RemoteCacheBackend,
        include: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
    ): Pair<CacheManager, Path> {
        val dir = TestFiles.createTestTempDirectory("test-remote")
        SystemFileSystem.createDirectories(dir)
        val manager = CacheManager(config = CacheConfig(), cacheDir = dir)
        val remoteConfig = RemoteCacheConfig(backend = "http", url = "https://cache.example.com", include = include, exclude = exclude)
        manager.buildRemoteLayer(backend, remoteConfig, scope, NoOpLogger)
        return manager to dir
    }

    private fun dropLocalBlob(dir: Path) {
        check(CasBlobStore(Path(dir, CacheManager.CAS_BLOBS_DIR)).delete(CasDigest.of(content)))
    }

    @Test
    fun `put pushes the blob to the remote`() =
        runTest {
            val backend = FakeBackend()
            val (manager, _) = manager(this, backend)

            manager.getCasCache("state").put("k", content, TTL_MS)
            advanceUntilIdle()

            assertTrue(backend.blobs[CasDigest.of(content).hex].contentEquals(content))
        }

    /** `sharedBlobStore` is live, the remote layer reads the local blob back from it. */
    @Test
    fun `the remote layer stores and finds blobs in the shared blob store`() =
        runTest {
            val backend = FakeBackend()
            val (manager, _) = manager(this, backend)

            manager.getCasCache("state").put("k", content, TTL_MS)
            advanceUntilIdle()

            assertTrue(manager.sharedBlobStore.get(CasDigest.of(content))?.contentEquals(content) == true)
        }

    @Test
    fun `missing local blob is pulled from the remote`() =
        runTest {
            val backend = FakeBackend()
            val (manager, dir) = manager(this, backend)
            val cache = manager.getCasCache("state")
            cache.put("k", content, TTL_MS)
            advanceUntilIdle()
            dropLocalBlob(dir)

            assertNull(cache.get("k"), "local-only read must miss once the blob is gone")
            assertTrue(cache.fetch("k").contentEquals(content))
            assertTrue(cache.get("k").contentEquals(content), "pulled blob is stored locally")
        }

    @Test
    fun `file tier of a tiered cache falls back to the remote`() =
        runTest {
            val backend = FakeBackend()
            val (manager, dir) = manager(this, backend)
            val spec = CacheSpec(maxEntries = 10, tier = CacheTier.MEMORY_AND_FILE, serializer = StringSerializer)
            manager.getOrCreate("responses", spec).put("k", "state-blob")
            advanceUntilIdle()
            dropLocalBlob(dir)

            val fresh = CacheManager(config = CacheConfig(), cacheDir = dir)
            fresh.buildRemoteLayer(backend, RemoteCacheConfig(backend = "http", url = "https://cache.example.com"), this, NoOpLogger)

            assertEquals("state-blob", fresh.getOrCreate("responses", spec).get("k"))
        }

    @Test
    fun `excluded namespace is neither pushed nor pulled`() =
        runTest {
            assertNamespaceNotShared(exclude = listOf("state"))
        }

    @Test
    fun `namespace outside include is neither pushed nor pulled`() =
        runTest {
            assertNamespaceNotShared(include = listOf("responses"))
        }

    @Test
    fun `provider responses stay local under a default remote config`() =
        runTest {
            val backend = FakeBackend()
            val (manager, _) = manager(this, backend)
            manager.getCasCache("http-responses").put("k", content, TTL_MS)
            advanceUntilIdle()
            assertTrue(backend.blobs.isEmpty(), "http-responses pushed: ${backend.blobs.keys}")
        }

    @Test
    fun `provider responses are shared when include names them`() =
        runTest {
            val backend = FakeBackend()
            val (manager, _) = manager(this, backend, include = listOf("http-responses"))
            manager.getCasCache("http-responses").put("k", content, TTL_MS)
            advanceUntilIdle()
            assertTrue(backend.blobs[CasDigest.of(content).hex].contentEquals(content))
        }

    private suspend fun TestScope.assertNamespaceNotShared(
        include: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
    ) {
        val backend = FakeBackend()
        val (manager, dir) = manager(this, backend, include, exclude)
        val cache = manager.getCasCache("state")
        cache.put("k", content, TTL_MS)
        advanceUntilIdle()
        assertTrue(backend.blobs.isEmpty())

        backend.blobs[CasDigest.of(content).hex] = content
        dropLocalBlob(dir)
        val pulls = backend.pulls
        assertNull(cache.fetch("k"))
        assertNull(manager.getCasCache("state").fetch("missing"))
        assertEquals(pulls, backend.pulls, "a namespace the remote does not cover never reaches the backend")
    }

    @Test
    fun `state index pushed by one runner is found by a fresh runner`() =
        runTest {
            val backend = FakeBackend()
            val (first, _) = manager(this, backend)
            first.getCasCache(CacheManager.STATE_NAMESPACE).put("mr-1", content, TTL_MS)
            advanceUntilIdle()

            val (fresh, _) = manager(this, backend)
            val state = fresh.getCasCache(CacheManager.STATE_NAMESPACE)
            assertTrue(state.fetch("mr-1").contentEquals(content))
            assertEquals(CacheSource.REMOTE, state.indexSource())
            assertFalse(state.foundNothing())
            assertTrue(state.get("mr-1").contentEquals(content), "the pulled index is restored locally")
        }

    @Test
    fun `local state wins and does not touch the remote`() =
        runTest {
            val backend = FakeBackend()
            val (manager, _) = manager(this, backend)
            val state = manager.getCasCache(CacheManager.STATE_NAMESPACE)
            state.put("mr-1", content, TTL_MS)
            advanceUntilIdle()

            assertTrue(state.fetch("mr-1").contentEquals(content))
            assertEquals(CacheSource.LOCAL, state.indexSource())
            assertEquals(0, backend.pulls)
        }

    @Test
    fun `state found in no layer is a first run`() =
        runTest {
            val (manager, _) = manager(this, FakeBackend())
            val state = manager.getCasCache(CacheManager.STATE_NAMESPACE)
            assertFalse(state.foundNothing(), "nothing fetched yet")
            assertNull(state.fetch("mr-1"))
            assertEquals(CacheSource.NONE, state.indexSource())
            assertTrue(state.foundNothing())
        }

    @Test
    fun `a namespace other than state does not share its index`() =
        runTest {
            val backend = FakeBackend()
            val (first, _) = manager(this, backend)
            first.getCasCache("responses").put("k", content, TTL_MS)
            advanceUntilIdle()

            val (fresh, _) = manager(this, backend)
            assertNull(fresh.getCasCache("responses").fetch("k"))
        }

    private object StringSerializer : CacheSerializer<String, String> {
        override fun serializeKey(key: String): String = key

        override fun serializeValue(value: String): String = value

        override fun deserializeValue(data: String): String = data
    }

    @Test
    fun `a blob already stored locally is not pushed again and counts as a deduplicated write`() =
        runTest {
            val backend = FakeBackend()
            val (manager, _) = manager(this, backend)
            // not "state": its index entries are pushed too
            val cache = manager.getCasCache("responses")

            cache.put("a", content, TTL_MS)
            cache.put("b", content, TTL_MS)
            advanceUntilIdle()

            assertEquals(1, backend.pushes)
            assertEquals(1L, manager.aggregateStats().perCache["remote"]?.deduplicatedWrites)
        }

    private companion object {
        const val TTL_MS = 60_000L
    }
}
