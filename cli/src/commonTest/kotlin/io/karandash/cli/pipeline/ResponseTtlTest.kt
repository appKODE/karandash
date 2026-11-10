package io.karandash.cli.pipeline

import io.karandash.cache.CacheConfig
import io.karandash.cache.ResponsesCacheConfig
import io.karandash.config.Config
import io.karandash.config.TimeoutConfig
import io.karandash.duration.KarandashDuration
import kotlin.test.Test
import kotlin.test.assertEquals

/** Provider response TTL: `cache.responses.ttl`, then `timeouts.cacheTtl`, then `cache.defaultTtl`, then 60s. */
class ResponseTtlTest {
    private val fiveMinutes = KarandashDuration.ofMinutes(5)
    private val twoHours = KarandashDuration.ofHours(2)

    @Test
    fun `cache defaultTtl applies when nothing more specific is set`() {
        val ttl = PipelineInfrastructure.responseTtl(CacheConfig(defaultTtl = twoHours), Config())
        assertEquals(twoHours, ttl)
    }

    @Test
    fun `responses ttl and timeouts cacheTtl win over cache defaultTtl`() {
        val withResponses = CacheConfig(defaultTtl = twoHours, responses = ResponsesCacheConfig(ttl = fiveMinutes))
        assertEquals(fiveMinutes, PipelineInfrastructure.responseTtl(withResponses, Config()))

        val withTimeouts = Config(timeouts = TimeoutConfig(cacheTtl = fiveMinutes))
        assertEquals(fiveMinutes, PipelineInfrastructure.responseTtl(CacheConfig(defaultTtl = twoHours), withTimeouts))
    }

    @Test
    fun `built-in default is unchanged without cache defaultTtl`() {
        assertEquals(ResponsesCacheConfig.DEFAULT_TTL, PipelineInfrastructure.responseTtl(CacheConfig(), Config()))
    }
}
