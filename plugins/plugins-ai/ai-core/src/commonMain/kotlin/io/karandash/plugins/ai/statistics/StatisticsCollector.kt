package io.karandash.plugins.ai.statistics

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.time.Clock

/**
 * Collects validation run statistics and persists them via [StatisticsStore].
 *
 * Call [collect] once per pipeline run after findings are finalised.
 *
 * @param store The persistence layer for statistics.
 */
class StatisticsCollector(
    private val store: StatisticsStore,
) {
    /**
     * Records statistics for the given [findings] into the store.
     *
     * @param mrId MR identifier, or null if unavailable.
     * @param findings All findings produced by the validation pipeline for this run.
     * @param platform Id of the platform service that fetched the MR, or null if unknown.
     * @param author Username of the MR author, or null if unknown.
     */
    fun collect(
        mrId: String?,
        findings: List<Finding>,
        platform: String? = null,
        author: String? = null,
    ) {
        val record = RunRecord(
            timestamp = Clock.System.now().epochSeconds,
            mrId = mrId,
            totalFindings = findings.size,
            errorCount = findings.count { it.severity == Severity.ERROR },
            warningCount = findings.count { it.severity == Severity.WARNING },
            infoCount = findings.count { it.severity == Severity.INFO },
            findingsByRule = findings.groupingBy { it.rule }.eachCount(),
            platform = platform,
            author = author,
        )
        store.record(record)
    }
}
