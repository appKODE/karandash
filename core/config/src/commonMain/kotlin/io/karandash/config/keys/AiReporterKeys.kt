package io.karandash.config.keys

/**
 * `reporters:` entry names served by the AI plugin rather than a format or output module.
 *
 * The AI pipeline extension reads them from the raw config; the missing-module check accepts them while an AI
 * report module is registered.
 */
object AiReporterKeys {
    const val SMART_DIGEST = "smartDigest"
    const val CHECK_RUN_ANNOTATION = "checkRunAnnotation"
    const val COMPLIANCE_EVIDENCE = "complianceEvidence"
    const val ANNOTATED_DIFF_HTML = "annotatedDiffHtml"
    const val TEAM_SCORECARD = "teamScorecard"
    const val DEVELOPER_GROWTH = "developerGrowth"
    const val RISK_HEATMAP = "riskHeatmap"
    const val DORA_METRICS = "doraMetrics"
    const val ROI_CALCULATOR = "roiCalculator"
    const val FINDING_TRANSFORM = "findingTransform"
    const val CROSS_PLATFORM_CONSISTENCY = "crossPlatformConsistency"
    const val FLAKY_RULE_DETECTOR = "flakyRuleDetector"
    const val POLICY_DRIFT_DETECTOR = "policyDriftDetector"
    const val AI_PREDICTIONS = "aiPredictions"

    /** Every AI-served `reporters:` entry name (camelCase). */
    val ALL: Set<String> = setOf(
        SMART_DIGEST,
        CHECK_RUN_ANNOTATION,
        COMPLIANCE_EVIDENCE,
        ANNOTATED_DIFF_HTML,
        TEAM_SCORECARD,
        DEVELOPER_GROWTH,
        RISK_HEATMAP,
        DORA_METRICS,
        ROI_CALCULATOR,
        FINDING_TRANSFORM,
        CROSS_PLATFORM_CONSISTENCY,
        FLAKY_RULE_DETECTOR,
        POLICY_DRIFT_DETECTOR,
        AI_PREDICTIONS,
    )
}
