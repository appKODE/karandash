package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.LocalizedText

/** Texts of the built-in correlation patterns' meta-findings in each output language. */
internal enum class CorrelationText(
    override val en: String,
) : LocalizedText {
    HIGH_RISK_UNREVIEWED(
        "High-risk: large MR with insufficient approval coverage",
    ),
    HIGH_RISK_UNREVIEWED_FIX(
        "Add an approval rule (approval) requiring a reviewer, or split the MR into smaller ones.",
    ),
    LARGE_UNDOCUMENTED(
        "Large MR with description issues — consider adding context for reviewers",
    ),
    LARGE_UNDOCUMENTED_FIX(
        "Describe what changed and why in the MR description, or split the MR into smaller ones.",
    ),
    SECURITY_WITHOUT_REVIEW(
        "Security findings detected without approval requirements — escalate for review",
    ),
    SECURITY_WITHOUT_REVIEW_FIX(
        "Add an approval rule (approval) requiring a reviewer, or resolve the security finding(s).",
    ),
}
