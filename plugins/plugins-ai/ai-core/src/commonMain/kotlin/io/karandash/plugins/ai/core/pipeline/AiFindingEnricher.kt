package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiService
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.LanguagePromptOverlays
import io.karandash.plugins.ai.core.isAiFinding
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Post-filter pipeline stage that enriches non-AI validation findings with
 * improved messages, explanations, and suggestions from an LLM.
 *
 * AI-generated findings (rule starting with `ai-`) are skipped by default - they
 * already contain rich messages from the AI analysis sub-rules.
 *
 * On any LLM failure the original findings are returned unchanged, so this
 * stage never causes a validation pipeline failure on its own.
 *
 * @param aiService The platform-level AI service used for completion calls.
 * @param config Enricher configuration controlling eligibility and limits.
 * @param model LLM model identifier to use for enrichment calls.
 * @param language `ai.responseLanguage`: the language of the rewritten texts; null or English needs no instruction.
 */
class AiFindingEnricher(
    private val aiService: AiService,
    private val config: EnricherConfig,
    private val model: String,
    private val logger: Logger = NoOpLogger,
    private val language: String? = null,
) {
    /**
     * Enriches eligible findings by calling the LLM for improved messages and suggestions.
     *
     * @param findings Original findings from the validation pipeline.
     * @return Enriched findings list, or the original list unchanged on failure or when disabled.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: enrichment pipeline - must not fail validation
    suspend fun enrich(findings: List<Finding>): List<Finding> {
        if (!config.enabled || !aiService.isAvailable) return findings

        val eligiblePairs = findings
            .mapIndexedNotNull { origIdx, f ->
                if (isEligible(f)) origIdx to f else null
            }.take(config.maxFindings)

        if (eligiblePairs.isEmpty()) return findings

        val response = try {
            aiService.complete(
                AiCompletionRequest(
                    prompt = buildPrompt(eligiblePairs.map { it.second }),
                    systemPrompt = LanguagePromptOverlays.withOutputLanguage(SYSTEM_PROMPT, language),
                    model = model,
                    maxTokens = ENRICHER_MAX_TOKENS,
                    temperature = ENRICHER_TEMPERATURE,
                ),
            )
        } catch (e: Exception) {
            // boundary: LLM provider call / untrusted LLM output
            e.rethrowCancellation()
            logger.debug("AiFindingEnricher: enrichment failed, returning original findings: ${e.message}")
            return findings
        }

        val enrichments = parseResponse(response.content)
        if (enrichments.isEmpty()) return findings

        val result = findings.toMutableList()
        for (enrichment in enrichments) {
            val (origIdx, original) = eligiblePairs.getOrNull(enrichment.index) ?: continue
            result[origIdx] = original.copy(
                message = enrichment.message?.takeIf { it.isNotBlank() } ?: original.message,
                explanation = enrichment.explanation?.takeIf { it.isNotBlank() } ?: original.explanation,
                suggestion = enrichment.suggestion?.takeIf { it.isNotBlank() } ?: original.suggestion,
            )
        }

        return result
    }

    private fun isEligible(finding: Finding): Boolean {
        if (config.enrichNonAiFindings && finding.isAiFinding()) return false
        return finding.severity !in config.skipSeverities
    }

    private fun buildPrompt(findings: List<Finding>): String {
        val lines = findings
            .mapIndexed { i, f ->
                "$i. Rule: ${f.rule} | Severity: ${f.severity.name} | Message: ${f.message}"
            }.joinToString("\n")
        return "Validation findings to enrich:\n$lines"
    }

    /**
     * Parses the LLM response JSON into a list of [FindingEnrichment] entries.
     *
     * Handles both raw JSON and JSON wrapped in a markdown code fence.
     * Returns an empty list on any parse failure.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: LLM response parsing - graceful degradation
    internal fun parseResponse(content: String): List<FindingEnrichment> {
        val jsonText = extractJson(content)
        return try {
            val root = Json.parseToJsonElement(jsonText).jsonObject
            val arr = root["enrichedFindings"]?.jsonArray ?: return emptyList()
            arr.mapNotNull { element ->
                val obj = element.jsonObject
                val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: return@mapNotNull null
                FindingEnrichment(
                    index = index,
                    message = obj["message"]?.jsonPrimitive?.content,
                    explanation = obj["explanation"]?.jsonPrimitive?.content,
                    suggestion = obj["suggestion"]?.jsonPrimitive?.content,
                )
            }
        } catch (e: Exception) {
            // boundary: LLM provider call / untrusted LLM output
            e.rethrowCancellation()
            logger.debug("AiFindingEnricher: failed to parse enrichment response: ${e.message}")
            emptyList()
        }
    }

    private fun extractJson(content: String): String {
        val trimmed = content.trim()
        val fenceStart = trimmed.indexOf("```json")
        if (fenceStart >= 0) {
            val afterFence = trimmed.indexOf('\n', fenceStart) + 1
            val endFence = trimmed.indexOf("```", afterFence)
            if (endFence > afterFence) return trimmed.substring(afterFence, endFence).trim()
        }
        val objStart = trimmed.indexOf('{')
        if (objStart >= 0) return trimmed.substring(objStart)
        return trimmed
    }

    private companion object {
        const val ENRICHER_MAX_TOKENS = 1500
        const val ENRICHER_TEMPERATURE = 0.1

        val SYSTEM_PROMPT = """
You are a validation finding enricher. Given a list of merge request validation findings,
produce improved messages, explanations, and suggestions for each.

Rules:
- Keep messages concise (under 120 characters)
- explanations should clarify why the rule matters for code quality
- suggestions must be specific and actionable
- Omit a field if you cannot improve on the original

Respond with ONLY valid JSON:
{
  "enrichedFindings": [
    {
      "index": 0,
      "message": "improved message",
      "explanation": "why this rule matters",
      "suggestion": "how to fix it"
    }
  ]
}
        """.trimIndent()
    }
}

/**
 * LLM-provided enrichment for a single finding at a given index.
 *
 * @param index Position in the eligible findings list sent to the LLM.
 * @param message Optional improved finding message.
 * @param explanation Optional explanation of why the finding matters.
 * @param suggestion Optional actionable suggestion for the author.
 */
@Serializable
data class FindingEnrichment(
    val index: Int,
    val message: String?,
    val explanation: String?,
    val suggestion: String?,
)
