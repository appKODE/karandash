package io.karandash.cache.remote

import io.karandash.cache.cas.CasDigest
import io.karandash.logger.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpCacheBackendTest {
    private val testDigest = CasDigest.of("hello".encodeToByteArray())
    private val testContent = "hello".encodeToByteArray()

    @Test
    fun pullReturnsContentOn200() =
        runTest {
            val fetcher = RemoteCacheFetcher { _, _, _, _ -> RemoteCacheResponse(200, testContent) }
            val backend = HttpCacheBackend("https://cache.example.com", fetcher, NoOpLogger)

            val result = backend.pull(testDigest)

            assertTrue(result.contentEquals(testContent))
        }

    @Test
    fun pullReturnsNullOn404() =
        runTest {
            val fetcher = RemoteCacheFetcher { _, _, _, _ -> RemoteCacheResponse(404) }
            val backend = HttpCacheBackend("https://cache.example.com", fetcher, NoOpLogger)

            val result = backend.pull(testDigest)

            assertNull(result)
        }

    @Test
    fun pushSendsContentViaPut() =
        runTest {
            var capturedMethod = ""
            var capturedUrl = ""
            var capturedBody: ByteArray? = null
            val fetcher = RemoteCacheFetcher { method, url, body, _ ->
                capturedMethod = method
                capturedUrl = url
                capturedBody = body
                RemoteCacheResponse(201)
            }
            val backend = HttpCacheBackend("https://cache.example.com", fetcher, NoOpLogger)

            assertTrue(backend.push(testDigest, testContent))

            assertEquals("PUT", capturedMethod)
            assertTrue(capturedUrl.startsWith("https://cache.example.com/cas/"))
            assertTrue(capturedUrl.contains(testDigest.shard))
            assertTrue(requireNotNull(capturedBody).contentEquals(testContent))
        }

    @Test
    fun pushReportsRefusedBlobAsNotStored() =
        runTest {
            val fetcher = RemoteCacheFetcher { _, _, _, _ -> RemoteCacheResponse(400) }
            val backend = HttpCacheBackend("https://cache.example.com", fetcher, NoOpLogger)

            assertFalse(backend.push(testDigest, testContent))
        }

    @Test
    fun existsReturnsTrueOn200() =
        runTest {
            val fetcher = RemoteCacheFetcher { _, _, _, _ -> RemoteCacheResponse(200) }
            val backend = HttpCacheBackend("https://cache.example.com", fetcher, NoOpLogger)

            assertTrue(backend.exists(testDigest))
        }

    @Test
    fun existsReturnsFalseOn404() =
        runTest {
            val fetcher = RemoteCacheFetcher { _, _, _, _ -> RemoteCacheResponse(404) }
            val backend = HttpCacheBackend("https://cache.example.com", fetcher, NoOpLogger)

            assertFalse(backend.exists(testDigest))
        }

    @Test
    fun factoryAppliesPrefixToHttpBlobUrls() =
        runTest {
            val urls = mutableListOf<String>()
            val fetcher = RemoteCacheFetcher { _, url, _, _ ->
                urls += url
                RemoteCacheResponse(404)
            }
            val config = RemoteCacheConfig(backend = "http", url = "https://cache.example.com/", prefix = "/team-a/")

            RemoteCacheBackendFactory.create(config, fetcher, NoOpLogger).pull(testDigest)

            assertEquals(listOf("https://cache.example.com/team-a/cas/${testDigest.shard}/${testDigest.remainder}"), urls)
        }
}
