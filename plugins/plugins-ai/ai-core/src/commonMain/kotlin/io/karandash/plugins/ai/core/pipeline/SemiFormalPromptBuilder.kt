package io.karandash.plugins.ai.core.pipeline

import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils

/**
 * Wraps an analyzer's system prompt with a semi-formal certificate template.
 *
 * The certificate template is inserted between the analyzer's base prompt and
 * the JSON response format suffix, so the LLM sees:
 * 1. Domain-specific instructions (from the analyzer)
 * 2. Structured reasoning requirements (certificate template)
 * 3. JSON format requirements (response format suffix)
 *
 * The LLM must fill all certificate sections, with the FINDINGS section
 * containing the structured JSON that the analyzer's parser expects.
 */
object SemiFormalPromptBuilder {
    private val CERTIFICATE_PREAMBLE = """
        |
        |## STRUCTURED REASONING REQUIREMENT
        |
        |Before producing your findings, you MUST complete a semi-formal reasoning
        |certificate. This certificate forces you to gather evidence systematically
        |and prevents unsupported claims.
        |
        |RULES:
        |1. Complete EVERY section in order — do not skip any section
        |2. Every file:line citation must reference a real file from the diff
        |3. Every "Supporting code" snippet must be copied verbatim from the diff
        |4. The FINDINGS section must contain ONLY the required JSON format
        |5. Do not include findings that lack supporting evidence
        |6. If you cannot find evidence for an issue, do not report it
        |
    """.trimMargin()

    private const val FINDINGS_INSTRUCTION = """

In the FINDINGS section, respond with ONLY the JSON in the required format below.
Do not include the certificate sections inside the JSON — the certificate is your
reasoning scratchpad, and the JSON in FINDINGS is your final structured output.
"""

    /**
     * Builds the effective system prompt with certificate template injected.
     *
     * @param basePrompt The analyzer's default system prompt.
     * @param extraInstructions Optional additional instructions.
     * @param systemPromptOverride Optional full override for the base prompt.
     * @param responseFormatSuffix The JSON schema the analyzer expects.
     * @param certificateType The certificate template to inject.
     * @return The assembled system prompt with certificate and format suffix.
     */
    fun buildPrompt(
        basePrompt: String,
        extraInstructions: String? = null,
        systemPromptOverride: String? = null,
        responseFormatSuffix: String?,
        certificateType: CertificateType,
    ): String {
        val base = when {
            systemPromptOverride != null -> {
                systemPromptOverride
            }

            extraInstructions != null -> {
                "$basePrompt\n\nAdditional instructions:\n$extraInstructions"
            }

            else -> {
                basePrompt
            }
        }

        val template = CertificateTemplates.forType(certificateType)

        val certificateBlock = buildString {
            append(CERTIFICATE_PREAMBLE)
            appendLine()
            append(template)
        }

        return if (responseFormatSuffix != null) {
            AiRuleAnalyzerUtils.effectivePrompt(
                basePrompt = "$base\n\n$certificateBlock\n\n$FINDINGS_INSTRUCTION",
                extraInstructions = null,
                systemPromptOverride = null,
                responseFormatSuffix = responseFormatSuffix,
            )
        } else {
            "$base\n\n$certificateBlock"
        }
    }
}
