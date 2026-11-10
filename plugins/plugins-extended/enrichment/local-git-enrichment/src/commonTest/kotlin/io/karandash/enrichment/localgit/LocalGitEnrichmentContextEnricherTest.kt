package io.karandash.enrichment.localgit

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalGitEnrichmentContextEnricherTest {
    private val enricher = LocalGitEnrichmentContextEnricher()
    private val mr = createTestMergeRequest()

    private fun testContext(workingDir: String = "/tmp/repo") = TestPluginContext(workingDirectory = workingDir)

    @Test
    fun `localGitContext dsl name is a live alias and initialize does not warn`() =
        runTest {
            val warnings = mutableListOf<String>()
            val ctx = object : PluginContext by testContext() {
                override val logger: Logger = object : Logger by NoOpLogger {
                    override fun warn(message: String) {
                        warnings += message
                    }
                }
            }
            assertTrue(enricher.initialize(ctx))
            assertTrue(warnings.isEmpty(), "no warning expected: $warnings")
            assertEquals("localGitContext", enricher.dslName)
        }

    @Test
    fun `provideBlock returns null when changes empty`() =
        runTest {
            assertNull(enricher.provideBlock(mr, emptyList(), JsonNull))
        }

    @Test
    fun `provideBlock returns null when working directory not set`() =
        runTest {
            // workingDirectory is blank by default (not initialized)
            assertNull(enricher.provideBlock(mr, emptyList(), JsonNull))
        }

    @Test
    fun `enrich always returns empty map`() =
        runTest {
            val ctx = AiContextEnricherContext(ruleId = null, existingContext = emptyMap())
            assertTrue(enricher.enrich(mr, ctx).isEmpty())
        }

    @Test
    fun `shutdown is idempotent and does not throw`() =
        runTest {
            enricher.shutdown()
            enricher.shutdown()
        }

    @Test
    fun `initialize returns true`() =
        runTest {
            val fresh = LocalGitEnrichmentContextEnricher()
            assertTrue(fresh.initialize(testContext()))
        }

    @Test
    fun `provideBlock returns null when workingDirectory blank after initialize`() =
        runTest {
            val fresh = LocalGitEnrichmentContextEnricher()
            fresh.initialize(testContext(workingDir = ""))
            val changes = listOf(
                FileChange(path = "src/Main.kt", changeType = ChangeType.MODIFIED, additions = 1),
            )
            assertNull(fresh.provideBlock(mr, changes, JsonNull))
        }

    @Test
    fun `module metadata is correct`() {
        val fresh = LocalGitEnrichmentContextEnricher()
        assertTrue(fresh.id == LocalGitEnrichmentContextEnricher.MODULE_ID)
        assertTrue(fresh.enricherPriority == LocalGitEnrichmentContextEnricher.ENRICHER_PRIORITY)
        assertTrue(!fresh.defaultEnabled)
    }

    @Test
    fun `handledEnrichmentIds includes local-git file frequency`() {
        val ids = LocalGitEnrichmentContextEnricher().handledEnrichmentIds()
        assertTrue(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY in ids)
    }

    @Test
    fun `provideBlock reads frequency data from enrichment config without calling analyzer`() =
        runTest {
            val fresh = LocalGitEnrichmentContextEnricher()
            // Not initialized - _workingDirectory is blank, so legacy path would return null.
            // Enrichment-context path must succeed without workingDirectory.
            val providerOutput = LocalGitEnrichmentProvider.fileFrequencyJson(
                mapOf("src/Auth.kt" to 12, "src/Main.kt" to 5),
                "6 months",
            )
            val config = buildJsonObject { put(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY, providerOutput) }
            val changes = listOf(
                FileChange(path = "src/Auth.kt", changeType = ChangeType.MODIFIED, additions = 1),
            )
            val block = fresh.provideBlock(mr, changes, config)
            assertNotNull(block)
            assertTrue(block.content.contains("src/Auth.kt: 12 commits"))
            assertTrue(block.content.contains("last 6 months"))
        }

    @Test
    fun `provideBlock returns null when enrichment config present but empty`() =
        runTest {
            val config = buildJsonObject {
                put(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY, buildJsonObject {})
            }
            assertNull(enricher.provideBlock(mr, emptyList(), config))
        }
}
