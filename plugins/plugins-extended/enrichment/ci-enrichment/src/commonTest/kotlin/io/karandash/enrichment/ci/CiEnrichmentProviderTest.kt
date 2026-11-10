package io.karandash.enrichment.ci

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CiEnrichmentProviderTest {
    private fun makeContext(config: String = "{}") =
        object : PluginContext {
            override val pluginConfig = io.karandash.models.support.KarandashJson.Default
                .parseToJsonElement(config)
            override val logger: Logger = NoOpLogger
            override val hostVersion = "test"
            override val workingDirectory = "/tmp"

            override fun env(name: String): String? = null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private class CapturingLogger : Logger {
        val messages = mutableListOf<String>()

        override fun debug(message: String) {}

        override fun info(message: String) {}

        override fun warn(message: String) {
            messages.add(message)
        }

        override fun error(message: String) {}
    }

    @Test
    fun warnsOnUnknownSubBlockKeyTypo() =
        runTest {
            val logger = CapturingLogger()
            val ctx = object : PluginContext {
                override val pluginConfig = io.karandash.models.support.KarandashJson.Default
                    .parseToJsonElement("""{"failed-jobss": {"enabled": true}}""")
                override val logger: Logger = logger
                override val hostVersion = "test"
                override val workingDirectory = "/tmp"

                override fun env(name: String): String? = null

                override fun warn(message: String) {}

                override fun log(message: String) {}
            }
            CiEnrichmentProvider().initialize(ctx)

            assertTrue(logger.messages.any { it.contains("enrichment.ci.failed-jobss") && it.contains("failed-jobs") })
        }

    @Test
    fun noWarningsOnWellFormedConfig() =
        runTest {
            val logger = CapturingLogger()
            val ctx = object : PluginContext {
                override val pluginConfig = io.karandash.models.support.KarandashJson.Default
                    .parseToJsonElement(
                        """{"platform":"gitlab","url":"https://gitlab.acme.com","project-id":"123",""" +
                            """"token":"secret","failed-jobs":{"enabled":true}}""",
                    )
                override val logger: Logger = logger
                override val hostVersion = "test"
                override val workingDirectory = "/tmp"

                override fun env(name: String): String? = null

                override fun warn(message: String) {}

                override fun log(message: String) {}
            }
            CiEnrichmentProvider().initialize(ctx)

            assertTrue(logger.messages.isEmpty(), "Expected no warnings, got: ${logger.messages}")
        }

    @Test
    fun `a configured API without a token gives a skip reason`() =
        runTest {
            val provider = CiEnrichmentProvider()
            provider.initialize(makeContext("""{"platform":"gitlab","url":"https://gitlab.acme.com","project-id":"123"}"""))

            assertTrue(provider.skipReason().orEmpty().contains("GITLAB_TOKEN"), provider.skipReason())
        }

    @Test
    fun `a platform without a pipeline API gives a skip reason`() =
        runTest {
            val provider = CiEnrichmentProvider()
            provider.initialize(makeContext("""{"platform":"bitbucket","url":"https://api.bitbucket.org","project-id":"1"}"""))

            assertTrue(provider.skipReason().orEmpty().contains("bitbucket"), provider.skipReason())
        }

    @Test
    fun `a configured API with a token or no API config gives no skip reason`() =
        runTest {
            val withToken = CiEnrichmentProvider()
            withToken.initialize(
                makeContext("""{"platform":"gitlab","url":"https://gitlab.acme.com","project-id":"123","token":"secret"}"""),
            )
            val noApi = CiEnrichmentProvider()
            noApi.initialize(makeContext())

            assertNull(withToken.skipReason())
            assertNull(noApi.skipReason())
        }

    @Test
    fun moduleMetadata() {
        val provider = CiEnrichmentProvider()
        assertEquals(CiEnrichmentProvider.ID, provider.id)
        assertEquals(ModuleType.ENRICHMENT_PROVIDER, provider.type)
        assertFalse(provider.defaultEnabled)
        assertTrue(provider.providedEnrichment.contains(EnrichmentKeys.CI_FAILED_JOBS))
        assertTrue(provider.providedEnrichment.contains(EnrichmentKeys.CI_COVERAGE_GAPS))
        assertTrue(provider.requiredEnrichment().isEmpty())
    }

    @Test
    fun initializeReturnsTrueWithEmptyConfig() =
        runTest {
            assertTrue(CiEnrichmentProvider().initialize(makeContext()))
        }

    @Test
    fun initializeToleratesUnknownConfigKeys() =
        runTest {
            assertTrue(CiEnrichmentProvider().initialize(makeContext("""{"unknown":true}""")))
        }

    @Test
    fun initializeReturnsTrueOnTypeMismatchedConfig() =
        runTest {
            // "platform" expects a String; decodeConfig must catch the SerializationException
            // and fall back to defaults rather than throw - CiEnrichmentConfig has no required
            // fields, so initialize() always succeeds regardless.
            assertTrue(CiEnrichmentProvider().initialize(makeContext("""{"platform": 123}""")))
        }

    @Test
    fun shutdownWithoutInitializeDoesNotThrow() = runTest { CiEnrichmentProvider().shutdown() }

    @Test
    fun shutdownIsIdempotent() =
        runTest {
            val provider = CiEnrichmentProvider()
            provider.shutdown()
            provider.shutdown()
        }

    @Test
    fun enrichWithNoFailedJobsReturnsEmpty() =
        runTest {
            val provider = CiEnrichmentProvider()
            provider.initialize(makeContext())
            val mr = io.karandash.testfixtures.createTestMergeRequest()
            val result = provider.enrich(mr, JsonObject(emptyMap()), io.karandash.api.context.EnrichmentContext.EMPTY)
            assertTrue(result.isEmpty())
            provider.shutdown()
        }
}

class FailedJobSummarizerTest {
    @Test
    fun extractsErrorLines() {
        val log = "Starting build\nsome normal output\nerror: compilation failed\nDone"
        val snippet = FailedJobSummarizer.extractSnippet(log)
        assertTrue(snippet.contains("error: compilation failed"))
    }

    @Test
    fun fallsBackToTailWhenNoErrorLines() {
        val log = "line1\nline2\nline3\nline4\nline5\nline6"
        val snippet = FailedJobSummarizer.extractSnippet(log)
        assertTrue(snippet.isNotEmpty())
    }

    @Test
    fun handlesEmptyLog() {
        val snippet = FailedJobSummarizer.extractSnippet("")
        assertTrue(snippet.isEmpty())
    }

    @Test
    fun truncatesToMaxChars() {
        val longLog = "error: " + "x".repeat(500)
        val snippet = FailedJobSummarizer.extractSnippet(longLog)
        assertTrue(snippet.length <= 200)
    }
}

class LcovParserTest {
    @Test
    fun parsesUncoveredLines() {
        val lcov = """
            SF:src/main/Foo.kt
            DA:10,0
            DA:11,0
            DA:12,5
            DA:13,0
            end_of_record
        """.trimIndent()
        val result = LcovParser.parse(lcov)
        val ranges = result["src/main/Foo.kt"] ?: error("missing key")
        assertEquals(2, ranges.size)
        assertEquals(10, ranges[0].startLine)
        assertEquals(11, ranges[0].endLine)
        assertEquals(13, ranges[1].startLine)
        assertEquals(13, ranges[1].endLine)
    }

    @Test
    fun returnsEmptyForFullyCoveredFile() {
        val lcov = "SF:src/main/Bar.kt\nDA:1,3\nDA:2,1\nend_of_record"
        val result = LcovParser.parse(lcov)
        assertTrue(result.isEmpty())
    }

    @Test
    fun handlesEmptyContent() {
        val result = LcovParser.parse("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun handlesMultipleFiles() {
        val lcov = """
            SF:a.kt
            DA:1,0
            end_of_record
            SF:b.kt
            DA:2,0
            end_of_record
        """.trimIndent()
        val result = LcovParser.parse(lcov)
        assertEquals(2, result.size)
        assertTrue(result.containsKey("a.kt"))
        assertTrue(result.containsKey("b.kt"))
    }
}

class JacocoParserTest {
    @Test
    fun parsesUncoveredLines() {
        val xml = """
            <?xml version="1.0"?>
            <report>
              <package name="io/karandash">
                <sourcefile name="Foo.kt">
                  <line nr="10" mi="2" ci="0" mb="0" cb="0"/>
                  <line nr="11" mi="0" ci="3" mb="0" cb="0"/>
                  <line nr="12" mi="1" ci="0" mb="0" cb="0"/>
                </sourcefile>
              </package>
            </report>
        """.trimIndent()
        val result = JacocoParser.parse(xml)
        val ranges = result["io/karandash/Foo.kt"] ?: error("missing key")
        assertEquals(2, ranges.size)
        assertEquals(10, ranges[0].startLine)
        assertEquals(12, ranges[1].startLine)
    }

    @Test
    fun returnsEmptyWhenAllLinesCovered() {
        val xml = """
            <package name="io/karandash">
              <sourcefile name="Bar.kt">
                <line nr="1" mi="0" ci="5" mb="0" cb="0"/>
              </sourcefile>
            </package>
        """.trimIndent()
        assertTrue(JacocoParser.parse(xml).isEmpty())
    }

    @Test
    fun handlesEmptyContent() {
        assertTrue(JacocoParser.parse("").isEmpty())
    }
}
