package io.karandash.cli.pipeline

import io.karandash.cache.CacheConfig
import io.karandash.cache.CacheManager
import io.karandash.cache.ResponsesCacheConfig
import io.karandash.cache.cas.ConditionalResponse
import io.karandash.config.Config
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/** `cache.responses.persist: true` keeps provider responses on disk for the next run; the default stays in memory. */
class ResponseCachePersistTest {
    private val dir = TestFiles.createTestTempDirectory("test-response-persist")

    private suspend fun runOnce(
        persist: Boolean,
        fetches: MutableList<String>,
    ): String {
        SystemFileSystem.createDirectories(dir)
        val cacheConfig = CacheConfig(responses = ResponsesCacheConfig(persist = persist))
        val cache = checkNotNull(PipelineInfrastructure.buildResponseCache(CacheManager(cacheConfig, dir), cacheConfig, Config()))
        return cache
            .getOrFetch("gitlab", "GET", "https://x/mr/1") {
                fetches += "fetch"
                ConditionalResponse(200, "body-${fetches.size}")
            }.content
    }

    @Test
    fun `persist true serves the second run from the disk tier`() =
        runTest {
            val fetches = mutableListOf<String>()
            runOnce(persist = true, fetches)
            assertEquals("body-1", runOnce(persist = true, fetches))
            assertEquals(1, fetches.size)
        }

    @Test
    fun `persist defaults to off and every run fetches again`() =
        runTest {
            val fetches = mutableListOf<String>()
            runOnce(persist = false, fetches)
            runOnce(persist = false, fetches)
            assertEquals(2, fetches.size)
        }
}
