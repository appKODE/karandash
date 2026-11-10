package io.karandash.cache.cas

import io.karandash.api.errors.ConfigException
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConditionalFetchCacheTest {
    private val dir = TestFiles.createTestTempDirectory("karandash-cfc-test").also {
        SystemFileSystem.createDirectories(it)
    }
    private val cas = CasCache(baseDir = dir, namespace = "downloads")
    private val cache = ConditionalFetchCache(cas)

    @AfterTest
    fun tearDown() = TestFiles.deleteRecursively(dir)

    @Test
    fun `miss fetches once and a live entry is served from the cache`() =
        runTest {
            val etags = mutableListOf<String?>()
            val fetch = ConditionalFetch { etag -> ConditionalResponse(200, "v1", "e1").also { etags.add(etag) } }
            assertEquals("v1", cache.getOrFetch("k", "https://x", TTL, fetch))
            assertEquals("v1", cache.getOrFetch("k", "https://x", TTL, fetch))
            assertEquals(listOf<String?>(null), etags)
        }

    @Test
    fun `expired entry with an ETag is revalidated and 304 keeps the cached body`() =
        runTest {
            cas.put("k", "old".encodeToByteArray(), -1L, etag = "e1")
            val etags = mutableListOf<String?>()
            val body = cache.getOrFetch("k", "https://x", TTL) { etag -> ConditionalResponse(304, "").also { etags.add(etag) } }
            assertEquals("old", body)
            assertEquals(listOf<String?>("e1"), etags)
            assertEquals("old", cas.get("k")?.decodeToString())
        }

    @Test
    fun `full fetch that is not 200 fails naming the url`() =
        runTest {
            val error = assertFailsWith<ConfigException> {
                cache.getOrFetch("k", "https://x", TTL) { ConditionalResponse(500, "") }
            }
            assertTrue(error.message.orEmpty().contains("https://x"), error.message)
        }

    @Test
    fun `clear removes only keys with the prefix`() =
        runTest {
            cas.put("extends-a", "a".encodeToByteArray(), TTL)
            cas.put("other", "b".encodeToByteArray(), TTL)
            CasConditionalStore(cas).clear("extends-")
            assertEquals(listOf("other"), cas.keys())
        }

    private companion object {
        const val TTL = 60_000L
    }
}
