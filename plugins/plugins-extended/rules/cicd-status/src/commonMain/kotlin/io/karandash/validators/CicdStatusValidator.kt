package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.config.BlockMode
import io.karandash.utils.finding.FindingFactory

class CicdStatusValidator(
    private val config: CicdStatusConfig
) {
    private companion object {
        private val DEFAULT_SUGGESTION = CicdStatusText.SUGGESTION.en
        private val DEFAULT_EXPLANATION = CicdStatusText.EXPLANATION.en
        private val successStates = setOf("success", "passed", "pass", "ok", "succeeded")
        private val failingStates = setOf("failed", "failure", "error", "canceled", "cancelled", "timed_out")
    }

    fun validate(mr: MergeRequest): List<Finding> {
        val findings = mutableListOf<Finding>()

        val status = normalizeStatus(mr.ciStatus)

        fun finding(message: String) =
            config.blockOnFailing.applyTo(
                FindingFactory.error(
                    rule = CicdStatusRuleModule.RULE_ID,
                    message = message,
                    suggestion = DEFAULT_SUGGESTION,
                    explanation = DEFAULT_EXPLANATION
                )
            )

        if (config.requirePassed) {
            if (status == null) {
                findings.add(
                    FindingFactory.warn(
                        rule = CicdStatusRuleModule.RULE_ID,
                        message = "CI/CD status is unavailable; unable to verify required passing pipeline",
                        suggestion = DEFAULT_SUGGESTION,
                        explanation = DEFAULT_EXPLANATION
                    )
                )
            } else if (status !in successStates) {
                finding("CI/CD pipeline is not passing (status: ${mr.ciStatus})")?.let(findings::add)
            }
        } else if (config.blockOnFailing == BlockMode.FAIL && status != null && status in failingStates) {
            findings.add(
                FindingFactory.error(
                    rule = CicdStatusRuleModule.RULE_ID,
                    message = "CI/CD pipeline is failing (status: ${mr.ciStatus})",
                    suggestion = DEFAULT_SUGGESTION,
                    explanation = DEFAULT_EXPLANATION
                )
            )
        }

        if (config.requiredJobs.isNotEmpty()) {
            if (mr.ciJobs.isEmpty()) {
                findings.add(
                    FindingFactory.warn(
                        rule = CicdStatusRuleModule.RULE_ID,
                        message = "CI/CD job-level status is unavailable; unable to verify required jobs: " +
                            config.requiredJobs.joinToString(", "),
                        suggestion = DEFAULT_SUGGESTION,
                        explanation = DEFAULT_EXPLANATION
                    )
                )
            } else {
                val jobsByName = mr.ciJobs.associateBy { it.name.lowercase() }
                config.requiredJobs.forEach { requiredJob ->
                    val key = requiredJob.lowercase()
                    val job = jobsByName[key]
                    when {
                        job == null -> {
                            finding("Required CI/CD job not found: $requiredJob")?.let(findings::add)
                        }

                        normalizeStatus(job.status) !in successStates -> {
                            finding("Required CI/CD job '$requiredJob' is not passing (status: ${job.status})")?.let(findings::add)
                        }
                    }
                }
            }
        }

        return findings
    }

    private fun normalizeStatus(status: String?): String? = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
