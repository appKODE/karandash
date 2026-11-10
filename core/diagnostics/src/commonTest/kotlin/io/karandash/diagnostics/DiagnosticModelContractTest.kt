package io.karandash.diagnostics

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Every single-field variant of the diagnostic models round trips with and without encoded defaults. */
class DiagnosticModelContractTest {
    private val sparse = Json(KarandashJson.Default) { encodeDefaults = false }

    private fun <T : Any> assertContract(
        serializer: KSerializer<T>,
        base: T,
        variants: List<T>,
    ) {
        (listOf(base) + variants).forEach { value ->
            listOf(KarandashJson.Default, sparse).forEach { json ->
                assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
            }
            assertNotEquals<Any?>(value, "other")
            assertEquals(value.hashCode(), value.hashCode())
        }
        variants.forEach { assertNotEquals(base, it) }
        assertEquals(variants.size, variants.toSet().size, "variants must be distinct")
    }

    private val rule = RuleDiagnostic("r", durationMs = 1, fromCache = false, findingCount = 0)
    private val summary = DiagnosticSummary(
        totalDurationMs = 1,
        peakMemoryBytes = 1,
        totalCpuUserMs = 1,
        totalCpuSystemMs = 1,
        totalGcCount = 1,
        totalGcTimeMs = 1,
        snapshotCount = 1,
        stageTimings = emptyMap(),
        ruleTimings = emptyList(),
        cache = CacheDiagnostic(0, 0),
        lastActiveStage = null,
    )

    @Test
    fun `report snapshot and event fields take part in equality and serialization`() {
        val report = DiagnosticReport()
        val snapshot = ResourceSnapshot(timestampMs = 1)
        val event = DiagnosticEvent(1, "http", "u")
        assertContract(
            DiagnosticReport.serializer(),
            report,
            listOf(
                report.copy(snapshots = listOf(snapshot)),
                report.copy(events = listOf(event)),
                report.copy(summary = summary),
                report.copy(context = PipelineContext()),
                report.copy(feedback = FeedbackDiagnostic()),
            ),
        )
        assertContract(
            ResourceSnapshot.serializer(),
            snapshot,
            listOf(
                snapshot.copy(timestampMs = 2),
                snapshot.copy(peakRssBytes = 1),
                snapshot.copy(currentRssBytes = 1),
                snapshot.copy(memoryTotalBytes = 1),
                snapshot.copy(memoryMaxBytes = 1),
                snapshot.copy(cpuUserTimeMs = 1),
                snapshot.copy(cpuSystemTimeMs = 1),
                snapshot.copy(gcCount = 1),
                snapshot.copy(gcTimeMs = 1),
                snapshot.copy(threadCount = 1),
                snapshot.copy(openFds = 1),
            ),
        )
        assertContract(
            DiagnosticEvent.serializer(),
            event,
            listOf(
                event.copy(timestampMs = 2),
                event.copy(type = "rule"),
                event.copy(name = "v"),
                event.copy(durationMs = 1),
                event.copy(metadata = mapOf("k" to "v")),
            ),
        )
    }

    @Test
    fun `summary fields take part in equality and serialization`() {
        assertContract(
            DiagnosticSummary.serializer(),
            summary,
            listOf(
                summary.copy(totalDurationMs = 2),
                summary.copy(peakMemoryBytes = 2),
                summary.copy(peakCurrentRssBytes = 2),
                summary.copy(totalCpuUserMs = 2),
                summary.copy(totalCpuSystemMs = 2),
                summary.copy(totalGcCount = 2),
                summary.copy(totalGcTimeMs = 2),
                summary.copy(maxThreadCount = 2),
                summary.copy(maxOpenFds = 2),
                summary.copy(snapshotCount = 2),
                summary.copy(stageTimings = mapOf("s" to 1L)),
                summary.copy(ruleTimings = listOf(rule)),
                summary.copy(cache = CacheDiagnostic(1, 0)),
                summary.copy(httpRequests = listOf(HttpDiagnostic("u", "GET", durationMs = 1))),
                summary.copy(httpTotalMs = 2),
                summary.copy(httpCount = 2),
                summary.copy(enrichmentTimings = listOf(EnrichmentDiagnostic("p", 1, false))),
                summary.copy(reporterTimings = listOf(ReporterDiagnostic("f", "o", 1, 1))),
                summary.copy(findingSummary = FindingSummaryDiagnostic(0, 0, 0, 0, true)),
                summary.copy(lastActiveStage = "s"),
                summary.copy(lastActiveStageDurationMs = 2),
                summary.copy(clientLifecycle = ClientLifecycleDiagnostic()),
                summary.copy(stageResources = mapOf("s" to StageResourceDelta())),
                summary.copy(memoryGrowthRateBytesPerSec = 2),
                summary.copy(slowestHttpUrl = "u"),
                summary.copy(slowestHttpDurationMs = 2),
                summary.copy(httpErrorCount = 2),
            ),
        )
    }

    @Test
    fun `timing parts take part in equality and serialization`() {
        assertContract(
            RuleDiagnostic.serializer(),
            rule,
            listOf(
                rule.copy(ruleId = "r2"),
                rule.copy(durationMs = 2),
                rule.copy(startMs = 2),
                rule.copy(endMs = 2),
                rule.copy(fromCache = true),
                rule.copy(findingCount = 2),
                rule.copy(errorCount = 2),
                rule.copy(warningCount = 2),
                rule.copy(infoCount = 2),
            ),
        )
        val cache = CacheDiagnostic(0, 0)
        assertContract(CacheDiagnostic.serializer(), cache, listOf(cache.copy(hits = 1), cache.copy(misses = 1)))
        val http = HttpDiagnostic("u", "GET", durationMs = 1)
        assertContract(
            HttpDiagnostic.serializer(),
            http,
            listOf(
                http.copy(url = "v"),
                http.copy(method = "POST"),
                http.copy(statusCode = 200),
                http.copy(durationMs = 2),
                http.copy(responseSizeBytes = 2),
                http.copy(error = "e"),
            ),
        )
        val clients = ClientLifecycleDiagnostic()
        assertContract(
            ClientLifecycleDiagnostic.serializer(),
            clients,
            listOf(clients.copy(clientCreates = 1), clients.copy(clientCloses = 1))
        )
        val delta = StageResourceDelta()
        assertContract(
            StageResourceDelta.serializer(),
            delta,
            listOf(
                delta.copy(rssStartBytes = 1),
                delta.copy(rssEndBytes = 1),
                delta.copy(rssDeltaBytes = 1),
                delta.copy(threadDelta = 1),
                delta.copy(fdDelta = 1),
            ),
        )
        val enrichment = EnrichmentDiagnostic("p", 1, false)
        assertContract(
            EnrichmentDiagnostic.serializer(),
            enrichment,
            listOf(
                enrichment.copy(providerId = "q"),
                enrichment.copy(durationMs = 2),
                enrichment.copy(fromCache = true),
                enrichment.copy(outputIds = listOf("o")),
            ),
        )
        val reporter = ReporterDiagnostic("f", "o", 1, 1)
        assertContract(
            ReporterDiagnostic.serializer(),
            reporter,
            listOf(reporter.copy(formatId = "g"), reporter.copy(outputId = "p"), reporter.copy(formatMs = 2), reporter.copy(outputMs = 2)),
        )
        val findings = FindingSummaryDiagnostic(0, 0, 0, 0, true)
        assertContract(
            FindingSummaryDiagnostic.serializer(),
            findings,
            listOf(
                findings.copy(total = 1),
                findings.copy(errors = 1),
                findings.copy(warnings = 1),
                findings.copy(infos = 1),
                findings.copy(passed = false),
            ),
        )
    }

    @Test
    fun `pipeline context fields take part in equality and serialization`() {
        val context = PipelineContext()
        assertContract(
            PipelineContext.serializer(),
            context,
            listOf(
                context.copy(platform = "gitlab"),
                context.copy(buildVariant = "native"),
                context.copy(os = "macos"),
                context.copy(karandashVersion = "1"),
                context.copy(configSource = "yaml"),
                context.copy(ruleCount = 1),
                context.copy(ruleIds = listOf("r")),
                context.copy(reporterIds = listOf("c")),
                context.copy(enrichmentProviderIds = listOf("e")),
                context.copy(mrUrl = "u"),
                context.copy(mrChangedLines = 1),
                context.copy(mrFileCount = 1),
                context.copy(platformConfidence = "high"),
                context.copy(platformSource = "env"),
            ),
        )
    }
}
