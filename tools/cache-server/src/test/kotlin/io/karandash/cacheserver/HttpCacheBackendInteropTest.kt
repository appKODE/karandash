@file:OptIn(ExperimentalPathApi::class)

package io.karandash.cacheserver

import io.karandash.cache.cas.CasBlobStore
import io.karandash.cache.cas.CasCache
import io.karandash.cache.cas.CasDigest
import io.karandash.cache.remote.RemoteCacheBackend
import io.karandash.cache.remote.RemoteCacheBackendFactory
import io.karandash.cache.remote.RemoteCacheConfig
import io.karandash.cache.remote.RemoteCacheLayer
import io.karandash.cli.cache.KtorRemoteCacheFetcher
import io.karandash.logger.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.files.Path as IoPath

/** The real client stack (`RemoteCacheBackendFactory` → `HttpCacheBackend` → `KtorRemoteCacheFetcher`) against the server. */
class HttpCacheBackendInteropTest {
    private val serverDir: Path = tempDir("cache-server-interop")
    private val runnerA: Path = tempDir("runner-a")
    private val runnerB: Path = tempDir("runner-b")
    private val remote = RemoteCacheConfig(backend = "http", url = "http://localhost", prefix = "team a/app", auth = TOKEN)

    @AfterTest
    fun cleanUp() {
        listOf(serverDir, runnerA, runnerB).forEach { it.deleteRecursively() }
    }

    private fun withServer(test: suspend (HttpClient) -> Unit) =
        testApplication {
            val config = CacheServerConfig(dataDir = serverDir.toString(), tokens = listOf(TOKEN))
            val metrics = CacheMetrics()
            application { cacheServerModule(config, BlobStore(serverDir, config.maxSizeBytes, NoOpLogger, metrics), metrics, NoOpLogger) }
            test(client)
        }

    private fun backend(
        client: HttpClient,
        config: RemoteCacheConfig = remote,
    ): RemoteCacheBackend = RemoteCacheBackendFactory.create(config, KtorRemoteCacheFetcher(client, config.auth), NoOpLogger)

    @Test
    fun `HttpCacheBackend push pull exists and delete work against the server`() =
        withServer { client ->
            val backend = backend(client)
            val content = "ai analysis result".encodeToByteArray()
            val digest = CasDigest.of(content)
            assertFalse(backend.exists(digest))
            assertNull(backend.pull(digest))
            assertTrue(backend.push(digest, content))
            assertTrue(backend.exists(digest))
            assertContentEquals(content, backend.pull(digest))
            backend.delete(digest)
            assertFalse(backend.exists(digest))
        }

    @Test
    fun `HttpCacheBackend without the token or with a wrong digest is refused`() =
        withServer { client ->
            val content = "blob".encodeToByteArray()
            val digest = CasDigest.of(content)
            assertFalse(backend(client, remote.copy(auth = null)).push(digest, content))
            assertFalse(backend(client).push(CasDigest.of("other".encodeToByteArray()), content))
            assertFalse(backend(client).exists(digest))
        }

    @Test
    fun `CasCache on a second runner pulls the blob the first runner pushed`() =
        withServer { client ->
            val content = "incremental ai cache entry".encodeToByteArray()
            val (cacheA, pushes) = casCache(runnerA, client)
            cacheA.put(KEY, content, TTL_MS)
            pushes.coroutineContext.job.children
                .toList()
                .joinAll()

            // the runner-local index travels (e.g. GitLab `cache:`); the blob comes from the server
            runnerA.resolve("index").copyToRecursively(runnerB.resolve("index"), followLinks = false, overwrite = true)
            val (cacheB, _) = casCache(runnerB, client)
            assertNull(cacheB.get(KEY))
            assertContentEquals(content, cacheB.fetch(KEY))
            assertContentEquals(content, cacheB.get(KEY))
            assertEquals(content.size, cacheB.get(KEY)?.size)
        }

    private fun casCache(
        dir: Path,
        client: HttpClient,
    ): Pair<CasCache, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob())
        val base = IoPath(dir.toString())
        val layer = RemoteCacheLayer(CasBlobStore(IoPath(base, "cas")), backend(client), remote, scope, NoOpLogger)
        return CasCache(base, "ai", remote = { layer }) to scope
    }

    private companion object {
        const val TOKEN = "interop-token"
        const val KEY = "mr-42"
        const val TTL_MS = 60_000L
    }
}
