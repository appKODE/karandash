package io.karandash.plugins.formats

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.finding.summarize
import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `local-git:contributor-stats` reaches the markdown summary as counts only, never names or emails. */
@OptIn(ExperimentalKarandashApi::class)
class MarkdownContributorStatsTest {
    private val report =
        ValidationReport(
            passed = true,
            findings = emptyList(),
            summary = emptyList<Finding>().summarize(),
            groupedByLocation = emptyMap(),
            inlineEligible = emptyList(),
            summaryOnly = emptyList(),
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
        )

    private fun stats(vararg commits: Int) =
        JsonObject(
            mapOf(
                "contributors" to JsonArray(
                    commits.mapIndexed { i, c ->
                        JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("Person$i Secret"),
                                "email" to JsonPrimitive("person$i@secret.example"),
                                "commits" to JsonPrimitive(c),
                            ),
                        )
                    },
                ),
                "period" to JsonPrimitive("90d"),
            ),
        )

    private suspend fun body(enrichment: EnrichmentContext?): String {
        val session = ModuleRegistryImpl().forRun(NoOpLogger)
        enrichment?.let { session.publishEnrichment(it) }
        return withContext(session.services) { MarkdownFormatModule().formatTyped(report, MarkdownFormatConfig(flowArtifactDir = "")).body }
    }

    @Test
    fun `contributor stats show counts and no names or emails`() =
        runTest {
            val md = body(EnrichmentContext(mapOf(EnrichmentKeys.LOCAL_GIT_CONTRIBUTOR_STATS to stats(12, 5, 2))))

            assertTrue("3 contributors, top contributor share 63%, commits per contributor: 12, 5, 2" in md, md)
            assertFalse("Person" in md || "secret.example" in md, md)
        }

    @Test
    fun `no contributor stats leaves the summary unchanged`() =
        runTest {
            val withEmpty = body(EnrichmentContext(mapOf(EnrichmentKeys.LOCAL_GIT_CONTRIBUTOR_STATS to stats())))

            assertEquals(body(null), withEmpty)
            assertFalse("contributor" in withEmpty, withEmpty)
        }
}
