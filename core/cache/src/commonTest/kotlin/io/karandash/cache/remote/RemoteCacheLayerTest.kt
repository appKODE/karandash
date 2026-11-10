package io.karandash.cache.remote

import io.karandash.cache.cas.CasBlobStore
import io.karandash.cache.cas.CasDigest
import io.karandash.duration.KarandashDuration
import io.karandash.logger.NoOpLogger
import io.karandash.size.KarandashSize
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCacheLayerTest {
    private val testContent = "test-blob-content".encodeToByteArray()

    private fun createTempDir(): Path = TestFiles.createTestTempDirectory("test-cas")

    private fun defaultConfig(
        push: Boolean = true,
        pull: Boolean = true,
    ): RemoteCacheConfig =
        RemoteCacheConfig(
            backend = "http",
            url = "https://cache.example.com",
            push = push,
            pull = pull,
            maxBlobSize = KarandashSize.ofMb(10),
            pushTimeout = KarandashDuration.ofSeconds(5),
            pullTimeout = KarandashDuration.ofSeconds(3),
        )

    @Test
    fun getReturnsLocalHitWithoutRemoteCall() =
        runTest {
            val casDir = createTempDir()
            val local = CasBlobStore(casDir)
            val digest = local.put(testContent)

            var remoteCalled = false
            val remote = object : StubRemoteCacheBackend() {
                override suspend fun pull(digest: CasDigest): ByteArray? {
                    remoteCalled = true
                    return null
                }
            }

            val layer = RemoteCacheLayer(local, remote, defaultConfig(), this, NoOpLogger)
            val result = layer.get(digest)

            assertNotNull(result)
            assertTrue(result.contentEquals(testContent))
            assertTrue(!remoteCalled, "Remote should not be called on local hit")
        }

    @Test
    fun getPullsFromRemoteOnLocalMiss() =
        runTest {
            val casDir = createTempDir()
            val local = CasBlobStore(casDir)
            val digest = CasDigest.of(testContent)

            val remote = object : StubRemoteCacheBackend() {
                override suspend fun pull(digest: CasDigest): ByteArray = testContent
            }

            val layer = RemoteCacheLayer(local, remote, defaultConfig(), this, NoOpLogger)
            val result = layer.get(digest)

            assertNotNull(result)
            assertTrue(result.contentEquals(testContent))
            assertTrue(local.exists(digest), "Content should be stored locally after remote pull")
        }

    @Test
    fun getReturnsNullWhenPullDisabled() =
        runTest {
            val casDir = createTempDir()
            val local = CasBlobStore(casDir)
            val digest = CasDigest.of(testContent)

            val remote = object : StubRemoteCacheBackend() {
                override suspend fun pull(digest: CasDigest): ByteArray = testContent
            }

            val layer = RemoteCacheLayer(local, remote, defaultConfig(pull = false), this, NoOpLogger)
            val result = layer.get(digest)

            assertNull(result)
        }

    @Test
    fun putPushesToRemoteAsync() =
        runTest {
            val casDir = createTempDir()
            val local = CasBlobStore(casDir)
            var pushed = false
            val remote = object : StubRemoteCacheBackend() {
                override suspend fun push(
                    digest: CasDigest,
                    content: ByteArray
                ): Boolean {
                    pushed = true
                    return true
                }
            }

            val layer = RemoteCacheLayer(local, remote, defaultConfig(), this, NoOpLogger)
            layer.put(testContent)
            advanceUntilIdle()

            assertTrue(pushed, "Content should be pushed to remote")
        }

    @Test
    fun putSkipsPushWhenDisabled() =
        runTest {
            val casDir = createTempDir()
            val local = CasBlobStore(casDir)
            var pushed = false
            val remote = object : StubRemoteCacheBackend() {
                override suspend fun push(
                    digest: CasDigest,
                    content: ByteArray
                ): Boolean {
                    pushed = true
                    return true
                }
            }

            val layer = RemoteCacheLayer(local, remote, defaultConfig(push = false), this, NoOpLogger)
            layer.put(testContent)
            advanceUntilIdle()

            assertTrue(!pushed, "Push should be skipped when disabled")
        }

    @Test
    fun remoteFailureReturnsNullGracefully() =
        runTest {
            val casDir = createTempDir()
            val local = CasBlobStore(casDir)
            val digest = CasDigest.of(testContent)

            val remote = object : StubRemoteCacheBackend() {
                override suspend fun pull(digest: CasDigest): ByteArray? {
                    error("Simulated network failure")
                }
            }

            val layer = RemoteCacheLayer(local, remote, defaultConfig(), this, NoOpLogger)
            val result = layer.get(digest)

            assertNull(result, "Should return null on remote failure, not throw")
        }

    @Test
    fun putSkipsPushWhenContentExceedsMaxBlobSize() =
        runTest {
            val casDir = createTempDir()
            val local = CasBlobStore(casDir)
            var pushed = false
            val remote = object : StubRemoteCacheBackend() {
                override suspend fun push(
                    digest: CasDigest,
                    content: ByteArray
                ): Boolean {
                    pushed = true
                    return true
                }
            }

            val config = RemoteCacheConfig(
                backend = "http",
                url = "https://cache.example.com",
                maxBlobSize = KarandashSize.ofBytes(5),
                pushTimeout = KarandashDuration.ofSeconds(5),
                pullTimeout = KarandashDuration.ofSeconds(3),
            )

            val layer = RemoteCacheLayer(local, remote, config, this, NoOpLogger)
            layer.put(testContent)
            advanceUntilIdle()

            assertTrue(!pushed, "Push should be skipped when content exceeds maxBlobSize")
        }

    @Test
    fun pullRejectsContentThatExceedsMaxBlobSize() =
        runTest {
            val local = CasBlobStore(createTempDir())
            val digest = CasDigest.of(testContent)
            val remote = object : StubRemoteCacheBackend() {
                override suspend fun pull(digest: CasDigest): ByteArray = testContent
            }
            val config = defaultConfig().copy(maxBlobSize = KarandashSize.ofBytes(5))

            val layer = RemoteCacheLayer(local, remote, config, this, NoOpLogger)

            assertNull(layer.get(digest))
            assertTrue(!local.exists(digest), "An oversized pulled blob must not be stored locally")
        }

    @Test
    fun pullFromRemoteRejectsContentWithDigestMismatch() =
        runTest {
            val casDir = createTempDir()
            val local = CasBlobStore(casDir)
            val digest = CasDigest.of(testContent)

            // Remote returns different content - digest won't match
            val tamperedContent = "tampered-blob-content".encodeToByteArray()
            val remote = object : StubRemoteCacheBackend() {
                override suspend fun pull(digest: CasDigest): ByteArray = tamperedContent
            }

            val layer = RemoteCacheLayer(local, remote, defaultConfig(), this, NoOpLogger)
            val result = layer.get(digest)

            assertNull(result, "Should return null when remote content digest does not match requested digest")
            assertTrue(!local.exists(digest), "Tampered content must not be stored locally")
        }

    @Test
    fun pullFromRemoteAcceptsContentWithMatchingDigest() =
        runTest {
            val casDir = createTempDir()
            val local = CasBlobStore(casDir)
            val digest = CasDigest.of(testContent)

            val remote = object : StubRemoteCacheBackend() {
                override suspend fun pull(digest: CasDigest): ByteArray = testContent
            }

            val layer = RemoteCacheLayer(local, remote, defaultConfig(), this, NoOpLogger)
            val result = layer.get(digest)

            assertNotNull(result)
            assertTrue(result.contentEquals(testContent))
            assertTrue(local.exists(digest), "Verified content should be stored locally")
        }
}

/** Stub for tests -- all methods throw by default so tests only override what they need. */
private open class StubRemoteCacheBackend : RemoteCacheBackend {
    override suspend fun exists(digest: CasDigest): Boolean = false

    override suspend fun pull(digest: CasDigest): ByteArray? = null

    override suspend fun push(
        digest: CasDigest,
        content: ByteArray
    ): Boolean = true

    override suspend fun delete(digest: CasDigest) {}

    override suspend fun shutdown() {}
}
