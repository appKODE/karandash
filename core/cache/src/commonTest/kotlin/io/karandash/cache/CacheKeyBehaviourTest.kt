package io.karandash.cache

import io.karandash.cache.cas.CasBlobStore
import io.karandash.cache.cas.CasDigest
import io.karandash.cache.remote.RemoteCacheBackend
import io.karandash.cache.remote.RemoteCacheBackendFactory
import io.karandash.cache.remote.RemoteCacheConfig
import io.karandash.cache.remote.RemoteCacheFetcher
import io.karandash.cache.remote.RemoteCacheLayer
import io.karandash.cache.remote.RemoteCacheResponse
import io.karandash.crypto.AwsCredentialChain
import io.karandash.duration.KarandashDuration
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.size.KarandashSize
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One behaviour test per `cache.*` key that is consumed inside `core/cache`. */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheKeyBehaviourTest {
    private fun manager(config: CacheConfig): CacheManager =
        CacheManager(config = config, cacheDir = TestFiles.createTestTempDirectory("karandash-cache-keys"))

    private fun fill(
        manager: CacheManager,
        name: String,
        count: Int,
    ): Int {
        val cache = manager.getOrCreate(name, CacheSpec<String, String>(maxEntries = 1000))
        repeat(count) { cache.put("k$it", "v$it") }
        return cache.size()
    }

    @Test
    fun `memory maxEntries 3 caps a cache sized through memoryMaxEntries`() =
        runTest {
            val manager = manager(CacheConfig(memory = MemoryCacheConfig(maxEntries = 3)))
            val cache = manager.getStringCache("any-cache", manager.memoryMaxEntries("any-cache"))

            repeat(8) { cache.put("k$it", "v$it") }

            assertEquals(3, manager.stats().getValue("any-cache").size)
        }

    @Test
    fun `memory overrides maxEntries 2 caps only the named cache`() {
        val manager = manager(
            CacheConfig(memory = MemoryCacheConfig(overrides = mapOf("small" to MemoryOverride(maxEntries = 2)))),
        )

        assertEquals(2, fill(manager, "small", 6))
        assertEquals(6, fill(manager, "other", 6))
    }

    @Test
    fun `glob maxEntries 4 caps the glob pattern cache`() {
        val manager = manager(CacheConfig(glob = GlobCacheConfig(maxEntries = 4)))

        assertEquals(4, fill(manager, GlobCacheConfig.CACHE_NAME, 9))
    }

    @Test
    fun `regex maxEntries 5 caps the safe regex cache`() {
        val manager = manager(CacheConfig(regex = RegexCacheConfig(maxEntries = 5)))

        assertEquals(5, fill(manager, RegexCacheConfig.CACHE_NAME, 9))
    }

    @Test
    fun `patterns maxEntries 6 caps the pattern resolver cache`() {
        val manager = manager(CacheConfig(patterns = PatternCacheConfig(maxEntries = 6)))

        assertEquals(6, fill(manager, PatternCacheConfig.CACHE_NAME, 9))
    }

    @Test
    fun `maxCacheSize 10 bytes evicts old entries on gc and unset keeps them`() {
        val bounded = manager(CacheConfig(maxCacheSize = 10L))
        val unbounded = manager(CacheConfig())
        listOf(bounded, unbounded).forEach { manager ->
            val cas = manager.getCasCache("state")
            repeat(3) { cas.put("k$it", "content$it".encodeToByteArray(), ttlMs = 3_600_000L) }
            manager.runGc()
        }

        assertEquals(1, bounded.getCasCache("state").keys().size)
        assertEquals(3, unbounded.getCasCache("state").keys().size)
    }

    private val content = "remote-blob-content".encodeToByteArray()

    private class StubBackend(
        private val pushDelayMs: Long = 0,
        private val pullDelayMs: Long = 0,
        private val blob: ByteArray? = null,
    ) : RemoteCacheBackend {
        var pushed = false
        var pulled = false

        override suspend fun exists(digest: CasDigest): Boolean = false

        override suspend fun pull(digest: CasDigest): ByteArray? {
            pulled = true
            delay(pullDelayMs)
            return blob
        }

        override suspend fun push(
            digest: CasDigest,
            content: ByteArray,
        ): Boolean {
            delay(pushDelayMs)
            pushed = true
            return true
        }

        override suspend fun delete(digest: CasDigest) = Unit

        override suspend fun shutdown() = Unit
    }

    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private fun store(): CasBlobStore = CasBlobStore(Path(TestFiles.createTestTempDirectory("karandash-remote-keys"), "cas"))

    private fun remote(
        push: Boolean = true,
        pull: Boolean = true,
        maxBlobSize: KarandashSize = KarandashSize.ofMb(10),
        pushTimeout: KarandashDuration = KarandashDuration.ofSeconds(5),
        pullTimeout: KarandashDuration = KarandashDuration.ofSeconds(3),
    ) = RemoteCacheConfig(
        backend = "http",
        url = "https://cache.example.com",
        push = push,
        pull = pull,
        maxBlobSize = maxBlobSize,
        pushTimeout = pushTimeout,
        pullTimeout = pullTimeout,
    )

    @Test
    fun `remote push false never uploads a stored blob`() =
        runTest {
            val backend = StubBackend()
            RemoteCacheLayer(store(), backend, remote(push = false), this, NoOpLogger).put(content)
            advanceUntilIdle()
            assertEquals(false, backend.pushed)
        }

    @Test
    fun `remote pull false never asks the remote for a missing blob`() =
        runTest {
            val backend = StubBackend(blob = content)
            val result = RemoteCacheLayer(store(), backend, remote(pull = false), this, NoOpLogger).get(CasDigest.of(content))
            assertNull(result)
            assertEquals(false, backend.pulled)
        }

    @Test
    fun `remote maxBlobSize 5 bytes keeps a larger blob local while 1 megabyte uploads it`() =
        runTest {
            val small = StubBackend()
            val big = StubBackend()
            RemoteCacheLayer(store(), small, remote(maxBlobSize = KarandashSize.ofBytes(5)), this, NoOpLogger).put(content)
            RemoteCacheLayer(store(), big, remote(maxBlobSize = KarandashSize.ofMb(1)), this, NoOpLogger).put(content)
            advanceUntilIdle()
            assertEquals(false, small.pushed)
            assertEquals(true, big.pushed)
        }

    @Test
    fun `remote pushTimeout 1 second abandons a slow upload and 120 seconds waits for it`() =
        runTest {
            val slowLogger = WarnLogger()
            val patientLogger = WarnLogger()
            val slow = StubBackend(pushDelayMs = 60_000)
            val patient = StubBackend(pushDelayMs = 60_000)
            RemoteCacheLayer(store(), slow, remote(pushTimeout = KarandashDuration.ofSeconds(1)), this, slowLogger).put(content)
            RemoteCacheLayer(store(), patient, remote(pushTimeout = KarandashDuration.ofSeconds(120)), this, patientLogger).put(content)
            advanceUntilIdle()
            assertEquals(false, slow.pushed)
            assertTrue(slowLogger.warnings.single().contains("timed out"), slowLogger.warnings.toString())
            assertEquals(true, patient.pushed)
            assertTrue(patientLogger.warnings.isEmpty())
        }

    @Test
    fun `remote pullTimeout 1 second gives up on a slow download and 120 seconds waits for it`() =
        runTest {
            val digest = CasDigest.of(content)
            val impatient = RemoteCacheLayer(
                store(),
                StubBackend(pullDelayMs = 60_000, blob = content),
                remote(pullTimeout = KarandashDuration.ofSeconds(1)),
                this,
                NoOpLogger,
            )
            val patient = RemoteCacheLayer(
                store(),
                StubBackend(pullDelayMs = 60_000, blob = content),
                remote(pullTimeout = KarandashDuration.ofSeconds(120)),
                this,
                NoOpLogger,
            )
            assertNull(impatient.get(digest))
            assertNotNull(patient.get(digest))
        }

    @Test
    fun `remote profile selects the shared credentials section that signs requests`() =
        runTest {
            val credentials = "[work]\naws_access_key_id = PROFILEKEY\naws_secret_access_key = profile-secret\n"
            val chain = AwsCredentialChain(env = mapOf("HOME" to "/home/ci")::get, readFile = { path ->
                credentials.takeIf {
                    path.name ==
                        "credentials"
                }
            })
            val auth = mutableListOf<String?>()
            val fetcher = RemoteCacheFetcher { _, _, _, headers ->
                auth += headers["Authorization"]
                RemoteCacheResponse(404)
            }
            val s3 = "https://bucket.s3.amazonaws.com"
            val digest = CasDigest.of(content)

            RemoteCacheBackendFactory
                .create(
                    RemoteCacheConfig(backend = "s3", url = s3, profile = "work"),
                    fetcher,
                    NoOpLogger,
                    chain
                ).pull(digest)
            RemoteCacheBackendFactory
                .create(
                    RemoteCacheConfig(backend = "s3", url = s3, profile = "missing"),
                    fetcher,
                    NoOpLogger,
                    chain
                ).pull(digest)

            assertTrue(auth[0]?.contains("Credential=PROFILEKEY/") == true, auth.toString())
            assertNull(auth[1])
        }
}
