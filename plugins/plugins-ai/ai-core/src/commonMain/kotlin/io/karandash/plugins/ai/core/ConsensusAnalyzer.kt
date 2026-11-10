package io.karandash.plugins.ai.core

import io.karandash.models.finding.Finding
import io.karandash.utils.finding.FindingFingerprint
import kotlinx.serialization.Serializable

/**
 * Analyzes multiple sets of findings from different models and returns only those
 * that reach a configurable cross-model agreement threshold.
 *
 * Findings are matched by a fingerprint derived from (rule, file path, normalised message prefix).
 * When multiple findings share a fingerprint, the one with the highest [io.karandash.models.finding.Severity]
 * is kept, enriched with consensus metadata.
 */
object ConsensusAnalyzer {
    /**
     * Filters findings to keep only those present in at least [minAgreement] of the [findingSets].
     *
     * @param findingSets One list of findings per model run.
     * @param minAgreement Minimum number of model runs that must produce a matching finding.
     *                     Defaults to [defaultMinAgreement] based on the number of sets.
     * @return A [ConsensusResult] containing the agreed-upon findings and agreement statistics.
     */
    fun intersect(
        findingSets: List<List<Finding>>,
        minAgreement: Int = defaultMinAgreement(findingSets.size),
    ): ConsensusResult {
        if (findingSets.isEmpty()) return ConsensusResult(emptyList(), 0, 1.0)

        if (findingSets.size == 1) {
            val findings = findingSets.first()
            return ConsensusResult(findings, findings.size, 1.0)
        }

        // Map fingerprint -> list of (modelIndex, finding)
        val fingerprintMap = mutableMapOf<String, MutableList<Pair<Int, Finding>>>()

        for ((index, findings) in findingSets.withIndex()) {
            for (finding in findings) {
                val fp = findingFingerprint(finding)
                fingerprintMap.getOrPut(fp) { mutableListOf() }.add(index to finding)
            }
        }

        val totalCandidates = fingerprintMap.size
        val agreedFindings = mutableListOf<Finding>()

        for ((_, entries) in fingerprintMap) {
            val distinctModelCount = entries.map { it.first }.toSet().size
            if (distinctModelCount < minAgreement) continue

            val best = checkNotNull(entries.minByOrNull { it.second.severity.ordinal }) {
                "consensus group must not be empty"
            }.second
            val modelIndices = entries.map { it.first }.toSet().sorted()
            val enriched = best.copy(
                metadata = best.metadata +
                    mapOf(
                        "consensusCount" to distinctModelCount.toString(),
                        "consensusModels" to modelIndices.joinToString(","),
                    )
            )
            agreedFindings.add(enriched)
        }

        val agreementRate = if (totalCandidates == 0) {
            1.0
        } else {
            agreedFindings.size.toDouble() / totalCandidates.toDouble()
        }

        return ConsensusResult(
            findings = agreedFindings,
            totalCandidates = totalCandidates,
            agreementRate = agreementRate,
        )
    }

    /**
     * Creates a stable fingerprint for a finding used to match similar findings across models.
     *
     * Delegates to [FindingFingerprint.generate] for consistent deduplication semantics
     * across the entire pipeline. If the finding already carries a pre-computed fingerprint,
     * that value is reused.
     *
     * @param finding The finding to fingerprint.
     * @return A string key suitable for equality-based grouping.
     */
    fun findingFingerprint(finding: Finding): String =
        finding.fingerprint
            ?: FindingFingerprint.generate(finding.rule, finding.location, finding.message)

    /**
     * Returns the minimum number of models that must agree for a finding to be kept.
     *
     * - 1 model  → 1 (all findings kept)
     * - 2 models → 2 (unanimous)
     * - 3+ models → majority (floor(n/2) + 1)
     *
     * @param modelCount Total number of model runs being compared.
     * @return The agreement threshold.
     */
    fun defaultMinAgreement(modelCount: Int): Int = if (modelCount <= 2) modelCount else (modelCount / 2) + 1
}

/**
 * Result of a [ConsensusAnalyzer.intersect] call.
 *
 * @param findings Findings that met the agreement threshold, enriched with consensus metadata.
 * @param totalCandidates Total number of unique fingerprints across all model runs.
 * @param agreementRate Fraction of candidate fingerprints that met the threshold (0.0–1.0).
 */
@Serializable
data class ConsensusResult(
    val findings: List<Finding>,
    val totalCandidates: Int,
    val agreementRate: Double,
) {
    /** Number of candidate findings that were filtered out due to insufficient agreement. */
    val filteredCount: Int get() = totalCandidates - findings.size
}
