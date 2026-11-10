package io.karandash.diagnostics

import io.karandash.api.finding.FindingFeedback
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticReportSerializationTest {
    @Test
    fun aiHealthCarriesTheContextBudgetLinePerRule() {
        val health = AiHealthDiagnostic(calls = 1, failed = 0, reason = "ok", context = mapOf("ai-review" to "budget 1000 tokens"))
        val json = KarandashJson.Default.encodeToString(AiDiagnostic(health))
        val context = checkNotNull(KarandashJson.Default.parseToJsonElement(json).jsonObject["health"]).jsonObject["context"]

        assertEquals(
            "budget 1000 tokens",
            context
                ?.jsonObject
                ?.get("ai-review")
                ?.jsonPrimitive
                ?.content
        )
        assertEquals(health, KarandashJson.Default.decodeFromString<AiDiagnostic>(json).health)
    }

    @Test
    fun aiSectionCarriesTheReviewFunnel() {
        val ai = AiDiagnostic(AiHealthDiagnostic(calls = 1, failed = 0), funnel = mapOf("sampled" to 12, "posted" to 4))
        val json = KarandashJson.Default.encodeToString(ai)
        val funnel = checkNotNull(KarandashJson.Default.parseToJsonElement(json).jsonObject["funnel"]).jsonObject

        assertEquals(12, funnel["sampled"]?.jsonPrimitive?.int)
        assertEquals(ai, KarandashJson.Default.decodeFromString<AiDiagnostic>(json))
    }

    @Test
    fun feedbackCountsFalsePositivesPerRuleUnderCamelCaseKeys() {
        val items = listOf(
            FindingFeedback(rule = "r1", fingerprint = "a", author = "alice"),
            FindingFeedback(rule = "r1", fingerprint = "b", author = "bob"),
            FindingFeedback(rule = "r2", fingerprint = "c", author = "alice"),
            FindingFeedback(rule = "r3", fingerprint = "d", reaction = "other", author = "alice"),
        )
        val json = KarandashJson.Default.encodeToString(DiagnosticReport(feedback = FeedbackDiagnostic.of(items)))
        val feedback = checkNotNull(KarandashJson.Default.parseToJsonElement(json).jsonObject["feedback"]).jsonObject

        assertEquals(3, feedback["falsePositives"]?.jsonPrimitive?.int)
        assertEquals(mapOf("r1" to 2, "r2" to 1), feedback["byRule"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int })
        assertEquals(
            items,
            KarandashJson.Default
                .decodeFromString<DiagnosticReport>(json)
                .feedback
                ?.items
        )
    }

    @Test
    fun emptyReportRoundTrips() {
        val report = DiagnosticReport()
        val json = KarandashJson.Default.encodeToString(report)
        val decoded = KarandashJson.Default.decodeFromString<DiagnosticReport>(json)
        assertEquals(report, decoded)
        assertTrue(decoded.snapshots.isEmpty())
        assertTrue(decoded.events.isEmpty())
        assertNull(decoded.summary)
    }

    @Test
    fun snapshotRoundTrips() {
        val snapshot = ResourceSnapshot(
            timestampMs = 500,
            peakRssBytes = 1024 * 1024,
            cpuUserTimeMs = 120,
            cpuSystemTimeMs = 30,
            gcCount = 5,
            gcTimeMs = 10,
        )
        val json = KarandashJson.Default.encodeToString(snapshot)
        val decoded = KarandashJson.Default.decodeFromString<ResourceSnapshot>(json)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun eventRoundTrips() {
        val event = DiagnosticEvent(
            timestampMs = 100,
            type = "stage.start",
            name = "configLoad",
            metadata = mapOf("source" to "yaml"),
        )
        val json = KarandashJson.Default.encodeToString(event)
        val decoded = KarandashJson.Default.decodeFromString<DiagnosticEvent>(json)
        assertEquals(event, decoded)
    }

    @Test
    fun fullReportRoundTrips() {
        val report = DiagnosticReport(
            snapshots = listOf(
                ResourceSnapshot(timestampMs = 0, peakRssBytes = 1000),
                ResourceSnapshot(timestampMs = 500, peakRssBytes = 2000),
            ),
            events = listOf(
                DiagnosticEvent(timestampMs = 0, type = "stage.start", name = "configLoad"),
                DiagnosticEvent(timestampMs = 50, type = "stage.end", name = "configLoad", durationMs = 50),
            ),
            summary = DiagnosticSummary(
                totalDurationMs = 1000,
                peakMemoryBytes = 2000,
                totalCpuUserMs = 80,
                totalCpuSystemMs = 20,
                totalGcCount = 3,
                totalGcTimeMs = 5,
                snapshotCount = 2,
                stageTimings = mapOf("configLoad" to 50L),
                ruleTimings = listOf(
                    RuleDiagnostic(ruleId = "titleFormat", durationMs = 30, fromCache = false, findingCount = 1),
                ),
                cache = CacheDiagnostic(hits = 0, misses = 1),
                lastActiveStage = null,
            ),
        )
        val json = KarandashJson.Default.encodeToString(report)
        val decoded = KarandashJson.Default.decodeFromString<DiagnosticReport>(json)
        assertEquals(report, decoded)
    }
}
