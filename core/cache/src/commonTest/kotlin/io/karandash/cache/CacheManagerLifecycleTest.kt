package io.karandash.cache

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.cache.cas.CasDigest
import io.karandash.cache.remote.RemoteCacheBackend
import io.karandash.cache.remote.RemoteCacheConfig
import io.karandash.duration.KarandashDuration
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.builtins.serializer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class CacheManagerLifecycleTest {
    private val dir = TestFiles.createTestTempDirectory("cache-lifecycle")

    private object NoRemote : RemoteCacheBackend {
        override suspend fun exists(digest: CasDigest): Boolean = false

        override suspend fun pull(digest: CasDigest): ByteArray? = null

        override suspend fun push(
            digest: CasDigest,
            content: ByteArray,
        ): Boolean = false

        override suspend fun delete(digest: CasDigest) = Unit

        override suspend fun shutdown() = Unit
    }

    @Test
    fun `string cache stores values in memory`() =
        runTest {
            val cache = CacheManager.withDefaults(dir).getStringCache("strings", maxEntries = 4)
            cache.put("k", "v")
            assertEquals("v", cache.get("k"))
        }

    @Test
    fun `persistent string cache survives a new manager on the same dir`() =
        runTest {
            val key = "rule:a:file:../x/y.kt"
            CacheManager(cacheDir = dir).getPersistentStringCache("p", 4, 1.hours).put(key, "v")
            assertEquals("v", CacheManager(cacheDir = dir).getPersistentStringCache("p", 4, 1.hours).get(key))
        }

    @Test
    fun `persistent string cache stays in memory when cache is disabled`() =
        runTest {
            CacheManager(CacheConfig(enabled = false), dir).getPersistentStringCache("p", 4, 1.hours).put("k", "v")
            assertNull(CacheManager(cacheDir = dir).getPersistentStringCache("p", 4, 1.hours).get("k"))
        }

    @Test
    fun `cas cache is created once per namespace`() {
        val manager = CacheManager(cacheDir = dir)
        assertSame(manager.getCasCache("state"), manager.getCasCache("state"))
        manager.shutdown()
        assertNull(manager.remoteLayer)
    }

    @Test
    fun `second remote layer is refused until shutdown`() =
        runTest {
            val manager = CacheManager(cacheDir = dir)
            val config = RemoteCacheConfig(backend = "http", url = "https://cache.example.com")
            val layer = manager.buildRemoteLayer(NoRemote, config, this)
            assertSame(layer, manager.remoteLayer)
            val error = assertFailsWith<IllegalStateException> { manager.buildRemoteLayer(NoRemote, config, this) }
            assertTrue(error.message.orEmpty().contains("already active"))
            manager.shutdown()
            assertNotNull(manager.buildRemoteLayer(NoRemote, config, this))
        }

    @Test
    fun `dedicated cache sections size their managed caches`() {
        val config = CacheConfig(
            glob = GlobCacheConfig(maxEntries = 1),
            regex = RegexCacheConfig(maxEntries = 1),
            patterns = PatternCacheConfig(maxEntries = 1),
            memory = MemoryCacheConfig(overrides = mapOf(PatternCacheConfig.CACHE_NAME to MemoryOverride(maxEntries = 2))),
        )
        val manager = CacheManager(config = config, cacheDir = dir)
        val expected = mapOf(GlobCacheConfig.CACHE_NAME to 1, RegexCacheConfig.CACHE_NAME to 1, PatternCacheConfig.CACHE_NAME to 2)
        expected.forEach { (name, size) ->
            val cache = manager.getOrCreate(name, CacheSpec<String, String>(maxEntries = 100))
            listOf("a", "b", "c").forEach { cache.put(it, it) }
            assertEquals(size, manager.stats(name)?.size, name)
        }
    }

    @Test
    fun `cache categories use kebab case serial names`() {
        val names = CacheCategory.entries.map { KarandashJson.Default.encodeToString(CacheCategory.serializer(), it) }
        assertEquals(
            listOf("provider-responses", "ai-responses", "validation-state", "extends", "plugins", "jira").map { "\"$it\"" },
            names,
        )
    }

    @Test
    fun `resolved cached value round trips and unresolved is a singleton`() {
        val serializer = CachedValue.Resolved.serializer(String.serializer())
        val value = CachedValue.Resolved("x")
        assertEquals(value, KarandashJson.Default.decodeFromString(serializer, KarandashJson.Default.encodeToString(serializer, value)))
        assertSame<CachedValue<String>>(CachedValue.Unresolved, CachedValue.Unresolved)
    }
}
