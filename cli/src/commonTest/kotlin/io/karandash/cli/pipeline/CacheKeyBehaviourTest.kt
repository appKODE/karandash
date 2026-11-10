package io.karandash.cli.pipeline

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.cache.CacheConfig
import io.karandash.cache.CacheManager
import io.karandash.cache.ResponsesCacheConfig
import io.karandash.cache.StateCacheConfig
import io.karandash.cache.cas.ConditionalResponse
import io.karandash.config.Config
import io.karandash.config.HttpConfig
import io.karandash.config.HttpResponseCacheConfig
import io.karandash.duration.KarandashDuration
import io.karandash.http.ProviderResponseCache
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `cache.*` and `http.responseCache.*` keys that the pipeline infrastructure turns into live caches. */
@OptIn(ExperimentalKarandashApi::class)
class CacheKeyBehaviourTest {
    private fun manager(config: CacheConfig) = CacheManager(config, TestFiles.createTestTempDirectory("karandash-cli-cache-keys"))

    @Test
    fun `cache enabled false builds no rule cache and true builds one`() {
        assertNull(PipelineInfrastructure.ruleCache(manager(CacheConfig(enabled = false)), noCache = false))
        assertNotNull(PipelineInfrastructure.ruleCache(manager(CacheConfig(enabled = true)), noCache = false))
    }

    @Test
    fun `cache state enabled false builds no state store`() {
        val off = CacheConfig(state = StateCacheConfig(enabled = false))
        val on = CacheConfig(state = StateCacheConfig(enabled = true))

        assertNull(PipelineInfrastructure.buildStateStore(off, manager(off)))
        assertNotNull(PipelineInfrastructure.buildStateStore(on, manager(on)))
    }

    @Test
    fun `cache enabled false builds no state store even with cache state enabled`() {
        val off = CacheConfig(enabled = false, state = StateCacheConfig(enabled = true))

        assertNull(PipelineInfrastructure.buildStateStore(off, manager(off)))
    }

    @Test
    fun `cache state ttl 5 minutes is the ttl the modules see`() {
        val config = CacheConfig(state = StateCacheConfig(ttl = KarandashDuration.ofMinutes(5)))

        val store = checkNotNull(PipelineInfrastructure.buildStateStore(config, manager(config)))

        assertEquals(KarandashDuration.ofMinutes(5), store.ttl)
    }

    @Test
    fun `cache responses enabled false builds no response cache`() {
        val off = CacheConfig(responses = ResponsesCacheConfig(enabled = false))

        assertNull(PipelineInfrastructure.buildResponseCache(manager(off), off, Config()))
        assertNotNull(PipelineInfrastructure.buildResponseCache(manager(CacheConfig()), CacheConfig(), Config()))
    }

    @Test
    fun `http responseCache maxEntries 2 keeps only two provider responses`() =
        runTest {
            val cacheConfig = CacheConfig()
            val manager = manager(cacheConfig)
            val config = Config(http = HttpConfig(responseCache = HttpResponseCacheConfig(maxEntries = 2)))
            val cache = checkNotNull(PipelineInfrastructure.buildResponseCache(manager, cacheConfig, config))

            repeat(6) { n -> cache.getOrFetch("gitlab", "GET", "https://x/mr/$n") { ConditionalResponse(200, "body-$n") } }

            assertEquals(2, manager.stats().getValue(ProviderResponseCache.CACHE_NAME).size)
        }
}
