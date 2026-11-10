package io.karandash.cli.pipeline

import io.karandash.config.Config
import io.karandash.config.HttpConfig
import io.karandash.config.HttpPaginationConfig
import io.karandash.config.HttpRetryConfig
import io.karandash.config.TimeoutConfig
import io.karandash.duration.KarandashDuration
import io.karandash.http.HttpClientSettings
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpClientSettingsWiringTest {
    @Test
    fun `unset http and timeouts blocks leave every setting null`() {
        assertEquals(HttpClientSettings(), PipelineInfrastructure.httpClientSettings(Config()))
    }

    @Test
    fun `configured blocks map to clamped client settings`() {
        val config = Config(
            timeouts = TimeoutConfig(http = KarandashDuration.ofSeconds(5)),
            http = HttpConfig(
                retry = HttpRetryConfig(maxAttempts = 50, baseDelay = KarandashDuration.ofMillis(200)),
                pagination = HttpPaginationConfig(maxPages = 0),
            ),
        )
        assertEquals(
            HttpClientSettings(
                timeout = KarandashDuration.ofSeconds(5),
                retry = HttpClientSettings.Retry(maxRetries = 10, baseDelay = KarandashDuration.ofMillis(200)),
                // maxPages: 0 reads every page
                maxPages = Int.MAX_VALUE,
            ),
            PipelineInfrastructure.httpClientSettings(config),
        )
    }

    @Test
    fun `KARANDASH_HTTP_TIMEOUT overrides timeouts http and invalid values are ignored with a warning`() {
        val config = Config(timeouts = TimeoutConfig(http = KarandashDuration.ofSeconds(5)))
        val warnings = mutableListOf<String>()
        val logger = object : Logger by NoOpLogger {
            override fun warn(message: String) {
                warnings += message
            }
        }

        fun timeout(value: String?) =
            PipelineInfrastructure
                .httpClientSettings(config, logger) {
                    if (it ==
                        PipelineInfrastructure.HTTP_TIMEOUT_ENV
                    ) {
                        value
                    } else {
                        null
                    }
                }.timeout

        assertEquals(KarandashDuration.ofSeconds(90), timeout("90"))
        assertEquals(KarandashDuration.ofSeconds(120), timeout("2m"))
        assertEquals(KarandashDuration.ofSeconds(5), timeout(null))
        assertTrue(warnings.isEmpty(), "$warnings")
        assertEquals(KarandashDuration.ofSeconds(5), timeout("soon"))
        assertEquals(KarandashDuration.ofSeconds(5), timeout("0"))
        assertEquals(2, warnings.count { "KARANDASH_HTTP_TIMEOUT" in it }, "$warnings")
    }
}
