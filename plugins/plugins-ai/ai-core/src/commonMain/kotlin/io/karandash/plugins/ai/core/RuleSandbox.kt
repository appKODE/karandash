package io.karandash.plugins.ai.core

import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Rule sandbox behind `karandash test-rule`: replays a rule against MR snapshots.
 *
 * Allows users to preview the impact of rule changes before deploying them.
 * Given a set of MR snapshots and a validation function, computes what findings
 * would be produced under the new configuration.
 */
class RuleSandbox {
    /**
     * Replays a rule against a set of historical MR snapshots.
     *
     * @param snapshots List of historical MR snapshots to test against.
     * @param ruleId The rule being tested.
     * @param validator Function that runs the rule and returns findings.
     * @return [SandboxResult] with detailed impact analysis.
     */
    fun replay(
        snapshots: List<MergeRequest>,
        ruleId: String,
        validator: (MergeRequest) -> List<Finding>,
    ): SandboxResult {
        if (snapshots.isEmpty()) {
            return SandboxResult(
                ruleId = ruleId,
                snapshotsReplayed = 0,
                findings = emptyList(),
                impactSummary = ImpactSummary(),
            )
        }

        val allFindings = mutableListOf<ReplayFinding>()
        var passCount = 0
        var failCount = 0
        val severityCounts = mutableMapOf<Severity, Int>()
        val affectedMrs = mutableSetOf<String>()

        for (snapshot in snapshots) {
            val findings = try {
                validator(snapshot)
            } catch (ignored: Exception) {
                // boundary: validator runs configured rule modules (plugin code)
                ignored.rethrowCancellation()
                emptyList()
            }

            if (findings.isEmpty()) {
                passCount++
            } else {
                failCount++
                affectedMrs.add(snapshot.id)
            }

            for (finding in findings) {
                severityCounts[finding.severity] = (severityCounts[finding.severity] ?: 0) + 1
                allFindings.add(
                    ReplayFinding(
                        mrId = snapshot.id,
                        mrTitle = snapshot.title,
                        finding = finding,
                    ),
                )
            }
        }

        val fireRate = failCount.toDouble() / snapshots.size

        return SandboxResult(
            ruleId = ruleId,
            snapshotsReplayed = snapshots.size,
            findings = allFindings,
            impactSummary = ImpactSummary(
                passCount = passCount,
                failCount = failCount,
                fireRate = fireRate,
                severityDistribution = severityCounts.toMap(),
                affectedMrCount = affectedMrs.size,
                recommendation = generateRecommendation(fireRate, snapshots.size),
            ),
        )
    }

    companion object {
        private const val HIGH_FIRE_RATE = 0.8
        private const val LOW_FIRE_RATE = 0.05
        private const val MIN_SAMPLE_SIZE = 10

        private fun generateRecommendation(
            fireRate: Double,
            sampleSize: Int
        ): String =
            when {
                sampleSize < MIN_SAMPLE_SIZE -> "Insufficient data — replay against more MRs for reliable results"
                fireRate > HIGH_FIRE_RATE -> "CAUTION: Rule would fire on ${(fireRate * 100).roundToInt()}% of MRs — likely too strict"
                fireRate < LOW_FIRE_RATE -> "Rule rarely fires — may be too lenient or not applicable to this codebase"
                else -> "Fire rate of ${(fireRate * 100).roundToInt()}% is within normal range"
            }
    }
}

/** A finding produced during replay, annotated with the MR context. */
@Serializable
data class ReplayFinding(
    val mrId: String,
    val mrTitle: String,
    val finding: Finding,
)

/** Overall result from a sandbox replay run. */
@Serializable
data class SandboxResult(
    val ruleId: String,
    val snapshotsReplayed: Int,
    val findings: List<ReplayFinding>,
    val impactSummary: ImpactSummary,
)

/** Summary statistics from the replay. */
@Serializable
data class ImpactSummary(
    val passCount: Int = 0,
    val failCount: Int = 0,
    val fireRate: Double = 0.0,
    val severityDistribution: Map<Severity, Int> = emptyMap(),
    val affectedMrCount: Int = 0,
    val recommendation: String = "",
)
