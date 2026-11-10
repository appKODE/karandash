package io.karandash.cache.cas

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.cache.CacheManager
import io.karandash.duration.KarandashDuration
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** the cache-side state store lives in core/state. */
class CasStateStoreTest {
    private val dir = TestFiles.createTestTempDirectory("cas-state-store")

    @OptIn(ExperimentalKarandashApi::class)
    @Test
    fun `state store adapter delegates to the cas cache`() {
        val cas = CacheManager(cacheDir = dir).getCasCache("ext")
        val store = cas.asStateStore(KarandashDuration.NEVER, reuseCached = false)
        store.put("k", "v".encodeToByteArray())
        assertContentEquals("v".encodeToByteArray(), store.get("k"))
        assertEquals(listOf("k"), store.keys())
        assertEquals(KarandashDuration.NEVER, store.ttl)
        assertEquals(false, store.reuseCached)
        store.invalidate("k")
        assertNull(store.get("k"))
        assertTrue(cas.asStateStore(KarandashDuration.ofMinutes(1)).reuseCached)
    }

    @OptIn(ExperimentalKarandashApi::class)
    @Test
    fun `state store fetch reads through the cas cache`() =
        runTest {
            val store = CacheManager(cacheDir = dir).getCasCache("ext-fetch").asStateStore(KarandashDuration.NEVER)
            store.put("k", "v".encodeToByteArray())
            assertContentEquals("v".encodeToByteArray(), store.fetch("k"))
            assertNull(store.fetch("missing"))
        }
}
