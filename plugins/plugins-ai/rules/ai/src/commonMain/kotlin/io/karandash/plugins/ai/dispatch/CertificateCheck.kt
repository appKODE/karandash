package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.logger.Logger
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.plugins.ai.core.pipeline.CertificateValidator
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * Semi-formal self-critique: records each certificate the analyzer receives, then runs
 * [CertificateValidator] over them and drops findings anchored at a `file:line` the certificate's
 * EVIDENCE cited but the diff does not back (file outside the MR, or a "Supporting code" snippet
 * that is not in that file's diff). One instance per analyzer dispatch.
 */
internal class CertificateCheck(
    private val delegate: AiService,
) : AiService by delegate {
    private val certificates = atomic(emptyList<String>())

    override suspend fun complete(
        request: AiCompletionRequest,
        providerId: String?,
    ): AiCompletionResponse = delegate.complete(request, providerId).also { response -> certificates.update { it + response.content } }

    fun filter(
        findings: List<Finding>,
        changes: List<FileChange>,
        ruleId: String,
        logger: Logger,
    ): List<Finding> {
        val results = certificates.value.map { CertificateValidator.validate(it, changes) }
        if (results.isEmpty()) return findings
        val unverified = results.flatMap { it.unverifiedCitations }.map { it.file to it.line }.toSet()
        val kept = findings.filterNot { finding ->
            val location = finding.location as? Location.FileRef
            location != null && (location.path to (location.line ?: 0)) in unverified
        }
        logger.info(
            "AI plugin [$ruleId]: certificate check — ${results.sumOf { it.verifiedCitations.size }} verified, " +
                "${unverified.size} unverified citation(s), ${results.count { !it.isComplete }} incomplete certificate(s); " +
                "dropped ${findings.size - kept.size} finding(s)",
        )
        return kept
    }
}
