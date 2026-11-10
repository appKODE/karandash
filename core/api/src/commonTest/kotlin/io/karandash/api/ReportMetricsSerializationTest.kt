package io.karandash.api

import io.karandash.api.finding.BaselineComparison
import io.karandash.api.finding.RuleSummary
import io.karandash.api.finding.ValidationMetrics
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportMetricsSerializationTest {
    @Test
    fun validationMetricsRoundTrips() {
        val original = ValidationMetrics(
            totalDurationMs = 1500,
            ruleExecutionTimes = mapOf("title-format" to 120L, "branch-naming" to 80L),
            enrichmentFetchTimeMs = 200,
            reportGenerationTimeMs = 50,
            cacheHits = 3,
            cacheMisses = 2,
        )
        val json = KarandashJson.Default.encodeToString(serializer<ValidationMetrics>(), original)
        val decoded = KarandashJson.Default.decodeFromString(serializer<ValidationMetrics>(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun validationMetricsDefaults() {
        val metrics = ValidationMetrics()
        assertEquals(0L, metrics.totalDurationMs)
        assertEquals(emptyMap(), metrics.ruleExecutionTimes)
        assertEquals(0L, metrics.enrichmentFetchTimeMs)
        assertEquals(0L, metrics.reportGenerationTimeMs)
        assertEquals(0, metrics.cacheHits)
        assertEquals(0, metrics.cacheMisses)
    }

    @Test
    fun ruleSummaryRoundTrips() {
        val original = RuleSummary(
            ruleId = "title-format",
            errors = 2,
            warnings = 1,
            infos = 0,
            hasFixSuggestions = true,
            executionTimeMs = 45,
            fromCache = false,
        )
        val json = KarandashJson.Default.encodeToString(serializer<RuleSummary>(), original)
        val decoded = KarandashJson.Default.decodeFromString(serializer<RuleSummary>(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun ruleSummaryDefaults() {
        val summary = RuleSummary(ruleId = "test-rule")
        assertEquals(0, summary.errors)
        assertEquals(0, summary.warnings)
        assertEquals(0, summary.infos)
        assertEquals(false, summary.hasFixSuggestions)
        assertEquals(0L, summary.executionTimeMs)
        assertEquals(false, summary.fromCache)
    }

    @Test
    fun baselineComparisonRoundTrips() {
        val original = BaselineComparison(
            baselineTimestamp = 1700000000L,
            newFindings = 5,
            resolvedFindings = 3,
            unchangedFindings = 12,
        )
        val json = KarandashJson.Default.encodeToString(serializer<BaselineComparison>(), original)
        val decoded = KarandashJson.Default.decodeFromString(serializer<BaselineComparison>(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun baselineComparisonDefaults() {
        val comparison = BaselineComparison()
        assertEquals(0L, comparison.baselineTimestamp)
        assertEquals(0, comparison.newFindings)
        assertEquals(0, comparison.resolvedFindings)
        assertEquals(0, comparison.unchangedFindings)
    }
}
