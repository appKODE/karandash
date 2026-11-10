package io.karandash.api.finding

import io.karandash.models.ActivePolicy
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Value contract for the report wire models: round trip, and every field takes part in equality. */
class ReportModelContractTest {
    /** Omits defaults, so the generated encoder's skip-default checks run too. */
    private val sparse = Json(KarandashJson.Report) { encodeDefaults = false }

    private fun <T : Any> assertContract(
        serializer: KSerializer<T>,
        base: T,
        variants: List<T>,
    ) {
        (listOf(base) + variants).forEach { value ->
            listOf(KarandashJson.Report, sparse).forEach { json ->
                assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
            }
            assertEquals(value.hashCode(), value.hashCode())
            assertNotEquals<Any?>(value, "other")
            assertTrue(value.toString().startsWith(value::class.simpleName.orEmpty()), value.toString())
        }
        variants.forEach { assertNotEquals(base, it) }
        assertEquals(variants.size, variants.toSet().size, "variants must be distinct")
    }

    private val error = Finding(Severity.ERROR, "e", "r1")
    private val warning = Finding(Severity.WARNING, "w", "r2")

    @Test
    fun `summarize splits findings by severity`() {
        val summary = listOf(error, warning, error.copy(severity = Severity.INFO), error).summarize()

        assertEquals(listOf(2, 1, 1, 4), listOf(summary.errors, summary.warnings, summary.info, summary.total))
        assertEquals(listOf(error, error), summary.errorFindings)
        assertEquals(0, emptyList<Finding>().summarize().total)
    }

    @Test
    fun `validation report fields each take part in equality and serialization`() {
        val base = ValidationReport(
            passed = true,
            findings = emptyList(),
            summary = emptyList<Finding>().summarize(),
            groupedByLocation = emptyMap(),
            inlineEligible = emptyList(),
            summaryOnly = emptyList(),
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata("1.0", 5),
        )
        val policy = ActivePolicy(
            condition = "c",
            matched = true,
            affectedModules = null,
            forcedEnabled = null,
            forcedSeverity = null,
            configOverrides = null,
        )
        assertContract(
            ValidationReport.serializer(),
            base,
            listOf(
                base.copy(passed = false),
                base.copy(findings = listOf(error)),
                base.copy(summary = listOf(error).summarize()),
                base.copy(groupedByLocation = mapOf(LocationKey("a.kt", 1) to listOf(error), LocationKey("b", null) to emptyList())),
                base.copy(inlineEligible = listOf(error)),
                base.copy(summaryOnly = listOf(warning)),
                base.copy(complianceChecklist = ComplianceStatus.entries.map { ComplianceItem("r", it) }),
                base.copy(explanations = mapOf("r" to "why")),
                base.copy(metadata = ReportMetadata("2.0", 6)),
                base.copy(appliedPolicies = listOf(policy)),
                base.copy(skippedRules = SkipCategory.entries.map { SkippedRuleInfo("r", "x", it, "p") }),
                base.copy(metrics = ValidationMetrics(1, mapOf("r" to 2), 3, 4, 5, 6)),
                base.copy(ruleSummaries = listOf(RuleSummary("r", 1, 2, 3, true, 4, true))),
                base.copy(enrichmentUsed = mapOf("r" to setOf("diff"))),
                base.copy(baselineComparison = BaselineComparison(1, 2, 3, 4)),
                base.copy(changedFiles = setOf("a.kt")),
                base.copy(provenance = ReviewProvenance(filters = listOf(FilterProvenance(FilterProvenance.FEEDBACK, 1)))),
            ),
        )
    }

    @Test
    fun `provenance fields each take part in equality and serialization`() {
        val base = ReviewProvenance()
        val entry = FindingHashChainEntry("r", "error", "h", "p", "c", "m", "1.0")
        val chain = FindingHashChain(listOf(entry), "ch", "c", "m", "1.0", 1)
        assertContract(
            ReviewProvenance.serializer(),
            base,
            listOf(
                base.copy(coverage = ReviewCoverage(2, 1)),
                base.copy(enrichers = ProvenanceStatus.entries.map { EnricherProvenance("e", it, "why", 3) }),
                base.copy(analyzers = listOf(AnalyzerProvenance("r", ProvenanceStatus.FAILED, "x", "m", 1, 2, 0.5, 3))),
                base.copy(analyzers = listOf(AnalyzerProvenance("r"))),
                base.copy(filters = listOf(FilterProvenance("dedupe", 2))),
                base.copy(chain = chain),
                base.copy(chain = chain.copy(entries = listOf(entry, entry.copy(previousHash = "h")))),
            ),
        )
    }

    @Test
    fun `provenance has details only with enrichers analyzers or filters`() {
        assertFalse(ReviewProvenance().hasDetails)
        assertFalse(ReviewProvenance(coverage = ReviewCoverage(1, 1)).hasDetails)
        assertTrue(ReviewProvenance(enrichers = listOf(EnricherProvenance("e", ProvenanceStatus.OK))).hasDetails)
        assertTrue(ReviewProvenance(analyzers = listOf(AnalyzerProvenance("r"))).hasDetails)
        assertTrue(ReviewProvenance(filters = listOf(FilterProvenance("f", 1))).hasDetails)
    }

    @Test
    fun `coverage describes counts and names left out files`() {
        assertEquals(
            "reviewed 3/5 files, 1 chunk skipped, 0 truncated",
            ReviewCoverage(5, 3, chunksSkipped = 1).describe(),
        )
        assertEquals(
            "reviewed 1/2 files, 2 chunks skipped, 1 truncated, left out: a, b, context left out: c, excluded: d",
            ReviewCoverage(2, 1, 2, 1, listOf("a", "b"), listOf("c"), listOf("d")).describe(),
        )
    }

    @Test
    fun `coverage names at most ten files and counts the rest`() {
        (0..25).forEach { count ->
            val names = List(count) { "f$it" }
            val text = ReviewCoverage(count, 0, dropped = names).describe()
            val listed = names.take(10).joinToString(", ")
            val expected = when {
                count == 0 -> ""
                count <= 10 -> ", left out: $listed"
                else -> ", left out: $listed and ${count - 10} more"
            }
            assertTrue(text.endsWith("0 truncated$expected"), text)
        }
    }

    @Test
    fun `coverage fields each take part in equality and serialization`() {
        val base = ReviewCoverage(2, 1)
        assertContract(
            ReviewCoverage.serializer(),
            base,
            listOf(
                base.copy(totalFiles = 3),
                base.copy(reviewedFiles = 2),
                base.copy(chunksSkipped = 1),
                base.copy(truncatedFiles = 1),
                base.copy(dropped = listOf("a")),
                base.copy(contextDropped = listOf("a")),
                base.copy(excluded = listOf("a")),
            ),
        )
    }
}
