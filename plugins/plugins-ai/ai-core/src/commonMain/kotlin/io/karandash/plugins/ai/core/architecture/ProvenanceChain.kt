package io.karandash.plugins.ai.core.architecture

import io.karandash.api.finding.FindingHashChain
import io.karandash.api.finding.FindingHashChainEntry
import io.karandash.crypto.sha256Hex
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.reporters.AiReportText

/**
 * Validation provenance chain: cryptographic audit trail for findings.
 *
 * Each finding carries provenance: rule version, config hash, MR snapshot hash.
 * The chain is tamper-evident - modifying any finding invalidates downstream hashes.
 *
 * Used for regulated industries (SOC2, ISO27001) requiring audit-grade evidence. Builds and verifies the
 * [FindingHashChain] carried on [io.karandash.api.finding.ReviewProvenance.chain].
 */
object ProvenanceChain {
    /**
     * Builds a provenance chain for a list of findings.
     *
     * @param findings The validation findings.
     * @param configHash Hash of the config used for this validation.
     * @param mrSnapshotHash Hash of the MR data at validation time.
     * @param toolVersion Version of the Karandash tool.
     * @return Provenance chain with per-finding hashes and a chain hash.
     */
    fun build(
        findings: List<Finding>,
        configHash: String,
        mrSnapshotHash: String,
        toolVersion: String,
    ): FindingHashChain {
        var previousHash = configHash
        val entries = findings.map { finding ->
            val contentHash = computeHash(
                "$previousHash|${finding.rule}|${finding.severity}|${finding.message}",
            )
            val entry = FindingHashChainEntry(
                findingRule = finding.rule,
                findingSeverity = finding.severity.name,
                findingHash = contentHash,
                previousHash = previousHash,
                configHash = configHash,
                mrSnapshotHash = mrSnapshotHash,
                toolVersion = toolVersion,
            )
            previousHash = contentHash
            entry
        }

        val chainHash = computeHash(
            entries.joinToString("|") { it.findingHash },
        )

        return FindingHashChain(
            entries = entries,
            chainHash = chainHash,
            configHash = configHash,
            mrSnapshotHash = mrSnapshotHash,
            toolVersion = toolVersion,
            findingCount = findings.size,
        )
    }

    /**
     * Verifies the integrity of a provenance chain.
     *
     * Checks that each entry's [FindingHashChainEntry.previousHash] matches the prior
     * entry's hash, and that the overall [FindingHashChain.chainHash] is consistent.
     *
     * @param result The chain to verify.
     * @return true if the chain is valid (no tampering detected).
     */
    fun verify(result: FindingHashChain): Boolean {
        if (result.entries.isEmpty()) return true

        var previousHash = result.configHash
        for (entry in result.entries) {
            if (entry.previousHash != previousHash) return false
            previousHash = entry.findingHash
        }

        val expectedChainHash = computeHash(
            result.entries.joinToString("|") { it.findingHash },
        )
        return expectedChainHash == result.chainHash
    }

    /**
     * Formats a human-readable report of the provenance chain.
     *
     * @param result The chain produced by [build].
     * @return A markdown-formatted report string.
     */
    fun formatReport(
        result: FindingHashChain,
        language: String? = null,
    ): String =
        buildString {
            appendLine(AiReportText.PROVENANCE_TITLE.of(language))
            appendLine(AiReportText.PROVENANCE_CHAIN_HASH.of(language, result.chainHash))
            appendLine(AiReportText.PROVENANCE_CONFIG_HASH.of(language, result.configHash))
            appendLine(AiReportText.PROVENANCE_MR_HASH.of(language, result.mrSnapshotHash))
            appendLine(AiReportText.PROVENANCE_TOOL_VERSION.of(language, result.toolVersion))
            appendLine(AiReportText.PROVENANCE_FINDINGS.of(language, result.findingCount))
            appendLine()
            for ((i, entry) in result.entries.withIndex()) {
                appendLine("$i. [${entry.findingSeverity}] ${entry.findingRule} → ${entry.findingHash.take(HASH_PREVIEW_LENGTH)}")
            }
        }

    private fun computeHash(input: String): String = sha256Hex(input)

    private const val HASH_PREVIEW_LENGTH = 8
}
