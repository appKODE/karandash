package io.karandash.plugins.ai.core.pipeline

/**
 * Structured certificate templates for semi-formal reasoning.
 *
 * Each template defines mandatory sections that the LLM must complete before
 * producing findings. This forces explicit evidence gathering and prevents
 * unsupported claims - the LLM cannot skip cases or hallucinate file locations.
 *
 * Based on Meta's "Agentic Code Reasoning" (arxiv 2603.01896) semi-formal
 * reasoning technique.
 */
object CertificateTemplates {
    /** Returns the certificate template for the given [type]. */
    fun forType(type: CertificateType): String =
        when (type) {
            CertificateType.CODE_REVIEW -> CODE_REVIEW_CERTIFICATE
            CertificateType.PATCH_ANALYSIS -> PATCH_ANALYSIS_CERTIFICATE
            CertificateType.CODE_QA -> CODE_QA_CERTIFICATE
            CertificateType.RISK_ASSESSMENT -> RISK_ASSESSMENT_CERTIFICATE
        }

    /**
     * Code review certificate - for fault localization style analysis.
     * Used by review-comments and code-suggestions analyzers.
     */
    val CODE_REVIEW_CERTIFICATE = """
        |## SEMI-FORMAL REASONING CERTIFICATE
        |
        |You MUST complete ALL sections below before producing findings.
        |Each section builds on the previous one. Do not skip any section.
        |
        |### DEFINITIONS
        |State the specific quality, correctness, or security properties you are checking.
        |Be precise about what constitutes a violation.
        |
        |### PREMISES
        |For each file in the diff, state explicit facts:
        |  P1: [file:line] — what this code does (one sentence)
        |  P2: [file:line] — what the calling context expects
        |  P3: [file:line] — any invariants or contracts this code must satisfy
        |
        |Only state premises you can verify from the diff. Do not assume behavior
        |of code not shown in the diff.
        |
        |### EXECUTION TRACE
        |For each potential issue, trace the concrete execution path:
        |  1. Entry point: [file:line]
        |  2. Data flow: variable X created at [file:line], passed to [file:line]
        |  3. Control flow: condition at [file:line] leads to [branch] when [condition]
        |  4. Terminal state: what happens at the end of this path
        |
        |### EVIDENCE
        |For each finding you will report, provide:
        |  - Claim: [specific assertion about the code]
        |  - Supporting code: [exact snippet copied from the diff, with file:line]
        |  - Counter-check: [why this is NOT a false positive — what alternative
        |    interpretation did you consider and rule out?]
        |
        |IMPORTANT: The "Supporting code" must be an exact substring of the diff.
        |Do not paraphrase or reconstruct code from memory.
        |
        |### FINDINGS
        |Based ONLY on the verified evidence above, produce findings in the
        |required JSON format. Every finding must trace back to an EVIDENCE entry.
        |Do not include findings without supporting evidence.
    """.trimMargin()

    /**
     * Patch analysis certificate - for comparing code changes.
     * Used by fix-suggestions analyzer.
     */
    val PATCH_ANALYSIS_CERTIFICATE = """
        |## SEMI-FORMAL REASONING CERTIFICATE
        |
        |You MUST complete ALL sections below before producing findings.
        |
        |### DEFINITIONS
        |Define what constitutes a correct fix for the issue being addressed.
        |State the expected behavior before and after the patch.
        |
        |### PREMISES
        |  P1: What the original code does at [file:line]
        |  P2: What the patched code does at [file:line]
        |  P3: What test behavior would change (if inferable from the diff)
        |
        |### BEHAVIORAL ANALYSIS
        |For each changed function or block:
        |  - Input domain: what inputs reach this code path
        |  - Output before patch: [concrete value or behavior]
        |  - Output after patch: [concrete value or behavior]
        |  - Edge cases: [inputs where before/after diverge]
        |
        |### EVIDENCE
        |For each finding:
        |  - Claim: [specific assertion about the patch correctness]
        |  - Supporting code: [exact snippet from diff, with file:line]
        |  - Counter-check: [why this claim holds even for edge cases]
        |
        |### FINDINGS
        |Produce findings in the required JSON format.
        |Every finding must trace back to an EVIDENCE entry.
    """.trimMargin()

    /**
     * Code QA certificate - for description quality, comment analysis, etc.
     * Used by description-quality, review-thread-analysis analyzers.
     */
    val CODE_QA_CERTIFICATE = """
        |## SEMI-FORMAL REASONING CERTIFICATE
        |
        |You MUST complete ALL sections below before producing findings.
        |
        |### DEFINITIONS
        |State the specific quality criteria being evaluated.
        |Define what constitutes compliance vs. violation.
        |
        |### FUNCTION TRACE TABLE
        |For each significant function or class in the diff:
        || Function | File:Line | Purpose | Dependencies |
        ||----------|-----------|---------|--------------|
        |
        |### DATA FLOW ANALYSIS
        |For key variables or data structures:
        |  Variable: [name]
        |  - Created at: [file:line]
        |  - Modified at: [file:line(s)]
        |  - Used at: [file:line(s)]
        |  - Crosses module boundary: [yes/no]
        |
        |### EVIDENCE
        |For each finding:
        |  - Claim: [specific assertion]
        |  - Supporting code: [exact snippet from diff, with file:line]
        |  - Counter-check: [why this is not a false positive]
        |
        |### FINDINGS
        |Produce findings in the required JSON format.
        |Every finding must trace back to an EVIDENCE entry.
    """.trimMargin()

    /**
     * Risk assessment certificate - for scoring change risk.
     * Used by risk-scoring analyzer.
     */
    val RISK_ASSESSMENT_CERTIFICATE = """
        |## SEMI-FORMAL REASONING CERTIFICATE
        |
        |You MUST complete ALL sections below before producing findings.
        |
        |### DEFINITIONS
        |Risk = probability of defect * blast radius of failure.
        |Define what constitutes low, medium, and high risk for this codebase.
        |
        |### PREMISES
        |  P1: Files changed: [list each file with +additions/-deletions]
        |  P2: Change scope: [localized to one module / crosses module boundaries]
        |  P3: Test coverage signals: [are tests included in the diff?]
        |  P4: Complexity indicators: [new conditionals, error handling changes,
        |      concurrency changes, API surface changes]
        |
        |### RISK FACTOR ANALYSIS
        |For each risk factor:
        |  - Factor: [name — e.g., "untested error path", "shared state mutation"]
        |  - Evidence: [file:line citation with exact code snippet]
        |  - Probability: [low/medium/high with one-sentence justification]
        |  - Blast radius: [low/medium/high — who/what is affected]
        |
        |### CONCLUSION
        |Derive the overall risk score [0.0-1.0] from the individual factors above.
        |State which factors dominate and why.
        |
        |### FINDINGS
        |Produce findings in the required JSON format.
        |The risk score must be derived from the analysis above, not guessed.
    """.trimMargin()
}
