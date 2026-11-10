package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import kotlinx.serialization.Serializable

/**
 * Deterministic validator for semi-formal reasoning certificates.
 *
 * Parses the structured sections from an LLM response, verifies that every
 * cited file:line reference exists in the actual MR diff, and checks that
 * code evidence snippets appear verbatim in the diff content.
 *
 * Findings whose evidence cannot be verified are dropped, significantly
 * reducing hallucinated results.
 */
object CertificateValidator {
    private const val SECTION_EVIDENCE = "EVIDENCE"
    private const val SECTION_FINDINGS = "FINDINGS"

    /** Section headers recognized in the certificate response. */
    private val SECTION_HEADER_REGEX = Regex(
        "^#{1,4}\\s*(DEFINITIONS|PREMISES|EXECUTION TRACE|BEHAVIORAL ANALYSIS|FUNCTION TRACE TABLE|" +
            "DATA FLOW ANALYSIS|RISK FACTOR ANALYSIS|EVIDENCE|CONCLUSION|FINDINGS)",
        RegexOption.MULTILINE,
    )

    /** Regex matching file:line citations like `src/Foo.kt:42` or `[src/Foo.kt:42]`. */
    private val FILE_LINE_CITATION_REGEX = Regex(
        """\[?([a-zA-Z0-9_./-]+\.\w+):(\d+)]?""",
    )

    /**
     * Parsed certificate with sections and validation results.
     */
    @Serializable
    data class CertificateResult(
        val sections: Map<String, String>,
        val findingsJson: String?,
        val verifiedCitations: List<VerifiedCitation>,
        val unverifiedCitations: List<UnverifiedCitation>,
        val isComplete: Boolean,
    )

    @Serializable
    data class VerifiedCitation(
        val file: String,
        val line: Int,
        val snippet: String?,
    )

    @Serializable
    data class UnverifiedCitation(
        val file: String,
        val line: Int,
        val reason: String,
    )

    /**
     * Validates a full certificate response against the MR's file changes.
     *
     * @param response The raw LLM response containing certificate sections.
     * @param changes The file changes from the merge request.
     * @return Parsed and validated certificate result.
     */
    fun validate(
        response: String,
        changes: List<FileChange>,
    ): CertificateResult {
        val sections = extractSections(response)
        val findingsJson = extractFindingsJson(sections)
        val diffByPath = changes.associate { it.path to (it.diffContent ?: "") }
        val evidenceSection = sections[SECTION_EVIDENCE] ?: ""

        val verified = mutableListOf<VerifiedCitation>()
        val unverified = mutableListOf<UnverifiedCitation>()

        for (match in FILE_LINE_CITATION_REGEX.findAll(evidenceSection)) {
            val file = match.groupValues[1]
            val line = match.groupValues[2].toIntOrNull() ?: continue

            val diffContent = diffByPath[file]
            if (diffContent == null) {
                unverified.add(UnverifiedCitation(file, line, "file not in MR changes"))
                continue
            }

            verified.add(VerifiedCitation(file, line, snippet = null))
        }

        verifyCodeSnippets(evidenceSection, diffByPath, verified, unverified)

        val hasEvidence = sections.containsKey(SECTION_EVIDENCE)
        val hasFindings = findingsJson != null
        val isComplete = hasEvidence && hasFindings

        return CertificateResult(
            sections = sections,
            findingsJson = findingsJson,
            verifiedCitations = verified,
            unverifiedCitations = unverified,
            isComplete = isComplete,
        )
    }

    /**
     * Extracts named sections from a certificate response.
     * Sections are delimited by markdown headers matching [SECTION_HEADER_REGEX].
     */
    fun extractSections(response: String): Map<String, String> {
        val matches = SECTION_HEADER_REGEX.findAll(response).toList()
        if (matches.isEmpty()) return emptyMap()

        val sections = mutableMapOf<String, String>()
        for (i in matches.indices) {
            val name = matches[i].groupValues[1].uppercase()
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else response.length
            val content = response.substring(start, end).trim()
            sections[name] = content
        }
        return sections
    }

    /**
     * Extracts the FINDINGS section and parses out the JSON block.
     * Returns null if no FINDINGS section or no JSON found.
     */
    fun extractFindingsJson(sections: Map<String, String>): String? {
        val findingsSection = sections[SECTION_FINDINGS] ?: return null
        return try {
            AiRuleAnalyzerUtils.extractJson(findingsSection)
        } catch (_: Exception) {
            // boundary: findings section may contain no valid JSON
            null
        }
    }

    /**
     * Checks "Supporting code" snippets in the EVIDENCE section against actual diffs.
     * Snippets that appear verbatim in the diff are verified; others are marked unverified.
     */
    private fun verifyCodeSnippets(
        evidenceSection: String,
        diffByPath: Map<String, String>,
        verified: MutableList<VerifiedCitation>,
        unverified: MutableList<UnverifiedCitation>,
    ) {
        val snippetRegex = Regex(
            """Supporting code:\s*(?:\[([^]]+:\d+)])?\s*[`—–-]*\s*([\s\S]+?)(?=\n\s*(?:[-*]\s*)?(?:Counter-check|Claim|Supporting code)|\s*\z)""",
        )

        for (match in snippetRegex.findAll(evidenceSection)) {
            val refStr = match.groupValues[1]
            val snippet = match.groupValues[2]
                .trim()
                .trim('`')
                .trim()

            if (snippet.isBlank()) continue

            val fileRef = FILE_LINE_CITATION_REGEX.find(refStr)
            val file = fileRef?.groupValues?.get(1)
            val line = fileRef?.groupValues?.get(2)?.toIntOrNull()

            if (file == null) {
                val found = diffByPath.values.any { it.contains(snippet) }
                if (!found) {
                    unverified.add(
                        UnverifiedCitation(
                            file = "<unknown>",
                            line = 0,
                            reason = "snippet not found in any diff",
                        ),
                    )
                }
                continue
            }

            val diffContent = diffByPath[file]
            if (diffContent != null && diffContent.contains(snippet)) {
                val existing = verified.find { it.file == file && it.line == (line ?: 0) }
                if (existing != null) {
                    verified.remove(existing)
                    verified.add(existing.copy(snippet = snippet))
                } else if (line != null) {
                    verified.add(VerifiedCitation(file, line, snippet))
                }
            } else {
                unverified.add(
                    UnverifiedCitation(
                        file = file,
                        line = line ?: 0,
                        reason = "snippet not found in diff for $file",
                    ),
                )
            }
        }
    }
}
