package io.karandash.config.pipeline

import io.karandash.cache.cas.CasCache
import io.karandash.logger.Logger
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.ExtendsEntry
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Config decision points log at DEBUG, and URLs in those lines go through `UrlSanitizer`. */
class DecisionDebugLoggingTest {
    private class CapturingLogger : Logger {
        val debugLines = mutableListOf<String>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            debugLines += message
        }

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    @Test
    fun `extends condition outcome and cache bypass are logged at debug without secrets`() =
        runTest {
            val logger = CapturingLogger()
            val evaluator = ConditionEvaluator(envProvider = { null }, platformName = "gitlab")
            ExtendsEntryResolver(conditionEvaluator = evaluator, logger = logger).resolveToSources(
                listOf(ExtendsEntry.UrlEntry(ConfigSource(url = "gh.yaml"), condition = "platform == 'github'")),
            )

            val fetcher = RemoteConfigFetcher(allowLocalhost = true)
            val cacheLayer = CacheLayer(
                fetcher = fetcher,
                casCache = CasCache(Path("build/tmp/decision-debug-cache"), "downloads"),
                logger = logger,
                enabled = false,
            )
            try {
                // Port 1 refuses the connection at once; only the bypass decision matters here.
                runCatching {
                    cacheLayer.getOrFetch(ConfigSource(url = "http://user:pw@127.0.0.1:1/c.yaml?private_token=s3cret"), null)
                }
            } finally {
                fetcher.close()
            }

            assertTrue(logger.debugLines.any { it.contains("condition not met, skipped") }, logger.debugLines.toString())
            assertTrue(
                logger.debugLines.any { it.startsWith("CacheLayer: bypass for http://127.0.0.1:1/c.yaml") },
                logger.debugLines.toString()
            )
            assertFalse(logger.debugLines.any { "s3cret" in it || "pw@" in it }, logger.debugLines.toString())
        }
}
