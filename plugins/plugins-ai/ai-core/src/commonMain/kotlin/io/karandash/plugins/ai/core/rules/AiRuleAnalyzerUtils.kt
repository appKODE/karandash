package io.karandash.plugins.ai.core.rules

import io.karandash.models.finding.Finding
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.config.AiAnalyzerTokensConfig
import io.karandash.plugins.ai.core.schema.ResponseParser
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.mr.HunkParser

/**
 * Shared utilities for AI rule analyzers.
 *
 * Eliminates duplication of JSON extraction from markdown code blocks,
 * diff truncation logic, and error-fallback finding creation across
 * all AI rule analyzer objects.
 */
object AiRuleAnalyzerUtils {
    /** [WellKnownMetadataKeys.ERROR] value of a [parseErrorFinding]. */
    const val PARSE_ERROR = "parse_error"

    /**
     * Resolves the effective max response tokens, scaling up from [configuredMax]
     * when the prompt is large enough that the default budget would likely cause
     * truncation.
     *
     * The heuristic: the response budget should be at least 60% of the estimated
     * prompt token count, bounded by `ai.analyzer.tokens` (min-response..max-auto-response).
     * If the user explicitly configured a value above the default, it is respected as-is.
     *
     * @param configuredMax The max tokens from config (e.g. `aiConfig.reviewMaxTokens`)
     * @param defaultMax The default constant for this analyzer (e.g. `DEFAULT_REVIEW_MAX_TOKENS`)
     * @param systemPrompt The assembled system prompt
     * @param userPrompt The user/diff prompt
     * @param tokens The `ai.analyzer.tokens` floor, ceiling and chars-per-token estimate
     * @return Effective max tokens to use in the completion request
     */
    fun resolveMaxResponseTokens(
        configuredMax: Int,
        defaultMax: Int,
        systemPrompt: String,
        userPrompt: String,
        tokens: AiAnalyzerTokensConfig = AiAnalyzerTokensConfig(),
    ): Int {
        // User explicitly set a non-default value - respect it
        if (configuredMax != defaultMax) return configuredMax

        val estimatedPromptTokens =
            (TokenUnit.tokensOf(systemPrompt) + TokenUnit.tokensOf(userPrompt)) * TokenUnit.CHARS_PER_TOKEN / tokens.charsPerTokenEstimate
        val scaledMin = (estimatedPromptTokens * 6 / 10).coerceIn(tokens.minResponse, tokens.maxAutoResponse)
        return maxOf(configuredMax, scaledMin)
    }

    /** Describes which extraction strategy was used (or attempted) for an AI response. */
    enum class ExtractionStrategy {
        /** Extracted from a markdown ` ```json ``` ` or ` ``` ``` ` fence. */
        FENCE,

        /** Found a balanced `{...}` or `[...]` block in the text. */
        BALANCED_BLOCK,

        /** Recovered truncated JSON by appending missing closing brackets. */
        TRUNCATION_RECOVERY,

        /** No structured extraction succeeded; raw content returned as-is. */
        RAW_FALLBACK,
    }

    /**
     * Diagnoses which extraction strategy [extractJson] would use for the given content.
     * Useful for error reporting when the extracted JSON still fails to parse.
     *
     * @param content Raw AI response text
     * @return A pair of (strategy, diagnostic message) describing what happened
     */
    fun diagnoseExtraction(content: String): Pair<ExtractionStrategy, String> {
        val stripped = ResponseParser.THINKING_BLOCK_REGEX.replace(content, "").trim()
        val trimmed = stripped.ifEmpty { content.trim() }
        val fenceMatch = ResponseParser.CODE_BLOCK_REGEX.find(trimmed)
        if (fenceMatch != null) {
            return ExtractionStrategy.FENCE to
                "extracted from markdown fence (${fenceMatch.groupValues[1].trim().length} chars)"
        }

        val extracted = ResponseParser.extractFirstJsonBlock(trimmed)
        if (extracted != null) {
            return ExtractionStrategy.BALANCED_BLOCK to
                "found balanced JSON block (${extracted.length} chars)"
        }

        val recovered = ResponseParser.recoverTruncatedJson(trimmed)
        if (recovered != null) {
            return ExtractionStrategy.TRUNCATION_RECOVERY to
                "response appears truncated (raw ${trimmed.length} chars); " +
                "recovered by closing open brackets. " +
                "This usually means the AI model hit its max output token limit. " +
                "Consider increasing reviewMaxTokens or reducing prompt/diff size."
        }

        return ExtractionStrategy.RAW_FALLBACK to
            "no JSON structure found in response (${trimmed.length} chars)"
    }

    /**
     * Common JSON response discipline instructions prepended to every format suffix.
     *
     * Covers all known LLM failure modes: markdown fencing, prose around JSON,
     * trailing commas, unescaped newlines inside strings, and extra whitespace.
     */
    private val JSON_FORMAT_PREAMBLE = """
        |CRITICAL RESPONSE FORMAT REQUIREMENTS:
        |You MUST respond with ONLY valid JSON. Your entire response must be parseable JSON.
        |
        |DO NOT include:
        |- Markdown fencing (no ```json``` or ``` blocks)
        |- Prose, explanations, or commentary before or after the JSON
        |- <think>, <reasoning>, or other XML-style thinking blocks
        |- Trailing commas after the last element in arrays or objects
        |- Literal newline characters inside string values (use \n instead)
        |
        |The response MUST start with { or [ and end with } or ].
        |
        |ARRAY FIELDS ARE ALWAYS ARRAYS:
        |Any field shown in the schema with [ ... ] brackets MUST be emitted as a JSON
        |array, even when it contains exactly one item. Never replace a one-item
        |array with a bare string or object.
        |  BAD:  "keyChanges": "single change"
        |  BAD:  "keyChanges": {"change": "single change"}
        |  GOOD: "keyChanges": ["single change"]
        |  GOOD: "keyChanges": []
        |This rule applies to every field whose schema value is wrapped in [ ... ].
        |
        |STRING ESCAPING — common mistakes that break JSON parsing:
        |- Double quotes inside strings MUST be escaped: use \" not "
        |  BAD:  "message": "Use pattern "^feat:" instead"
        |  GOOD: "message": "Use pattern \"^feat:\" instead"
        |- Backslashes MUST be escaped: use \\ not \
        |- Do NOT use backticks or markdown formatting inside string values
        |- Regex patterns must have all special characters JSON-escaped
    """.trimMargin()

    /**
     * Builds the effective system prompt by combining the base prompt, extra instructions
     * or a full override, and an optional response format block appended at the very end.
     *
     * When [responseFormatSuffix] is non-null, common JSON format discipline instructions are
     * prepended automatically, so strict formatting requirements
     * are the final instruction regardless of prompt layering.
     *
     * The system prompt is T0 and never cut: `ai.budget.max-input-tokens` bounds the whole prompt,
     * and a rule whose T0 alone is over it is skipped and reported.
     *
     * @param basePrompt Default builtin prompt for the analyzer
     * @param extraInstructions Optional additional instructions appended to the base prompt
     * @param systemPromptOverride Optional full replacement for the base prompt (user override)
     * @param responseFormatSuffix Optional prose example of the reply JSON - when present, common format
     *   discipline instructions are prepended and the whole block is appended last
     * @return The assembled system prompt
     */
    fun effectivePrompt(
        basePrompt: String,
        extraInstructions: String? = null,
        systemPromptOverride: String? = null,
        responseFormatSuffix: String? = null,
    ): String {
        val base = when {
            systemPromptOverride != null -> systemPromptOverride
            extraInstructions != null -> "$basePrompt\n\nAdditional instructions:\n$extraInstructions"
            else -> basePrompt
        }
        if (responseFormatSuffix == null) return base
        return "$base\n\n$JSON_FORMAT_PREAMBLE\n\nRequired JSON format:\n$responseFormatSuffix"
    }

    /** The JSON payload of an AI reply; [ResponseParser.extractJson] owns the repair. */
    fun extractJson(content: String): String = ResponseParser.extractJson(content)

    /**
     * Parses a unified diff and returns the set of line numbers (in the new file) that were added.
     * Delegates to [HunkParser.extractAddedLineNumbers].
     *
     * @param diffContent Raw unified diff string, or null for files with no diff
     * @return Set of new-file line numbers that correspond to `+` lines in the diff
     */
    fun extractAddedLines(diffContent: String?): Set<Int> = HunkParser.extractAddedLineNumbers(diffContent)

    /**
     * Maps a file path reported by the LLM onto an MR path: tolerates a leading `/`, `./` and the
     * `a/` / `b/` diff prefixes. Returns [path] unchanged when it matches or nothing matches.
     */
    fun normalizeDiffPath(
        path: String,
        known: Set<String>?,
    ): String {
        if (known == null || path in known) return path
        val stripped = path.trim().removePrefix("./").removePrefix("/")
        return listOf(stripped, stripped.removePrefix("a/"), stripped.removePrefix("b/")).firstOrNull { it in known } ?: path
    }

    /**
     * Snaps [line] to the nearest added line within [maxSnap] lines.
     * Delegates to [HunkParser.snapToAddedLine].
     *
     * @param line The AI-suggested line number to snap
     * @param added The set of added lines from [extractAddedLines]
     * @param maxSnap Maximum distance to search in either direction
     * @return The snapped line number, or null if none found within [maxSnap]
     */
    fun snapToAddedLine(
        line: Int,
        added: Set<Int>,
        maxSnap: Int = HunkParser.DEFAULT_MAX_SNAP,
    ): Int? = HunkParser.snapToAddedLine(line, added, maxSnap)

    /**
     * Extracts individual top-level elements from a JSON array identified by [arrayKey].
     *
     * Given a JSON string like `{"comments": [{...}, {...}, {...}]}`, returns each
     * array element as a separate string. Uses balanced-brace parsing to handle
     * nested objects and strings correctly, so malformed elements don't break
     * extraction of subsequent valid ones.
     *
     * @param json The full JSON string containing the array
     * @param arrayKey The key whose value is the target array (e.g. "comments")
     * @return List of individual element strings, empty if the array is not found
     */
    fun extractJsonArrayElements(
        json: String,
        arrayKey: String
    ): List<String> {
        val keyPattern = "\"$arrayKey\""
        val keyIdx = json.indexOf(keyPattern)
        if (keyIdx < 0) return emptyList()

        // Find the `[` that starts the array value after `"key" :`
        val afterKey = json.indexOf(':', keyIdx + keyPattern.length)
        if (afterKey < 0) return emptyList()
        val arrayStart = json.indexOf('[', afterKey)
        if (arrayStart < 0) return emptyList()

        val elements = mutableListOf<String>()
        var i = arrayStart + 1

        while (i < json.length) {
            // Skip whitespace and commas between elements
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            if (i >= json.length || json[i] == ']') break

            // We expect the start of an element (object, array, string, or primitive)
            val elementStart = i
            val startChar = json[elementStart]

            if (startChar == '{' || startChar == '[') {
                val extracted = extractBalancedBlock(json, elementStart)
                if (extracted != null) {
                    elements.add(extracted)
                    i = elementStart + extracted.length
                } else {
                    // Malformed element - skip to next comma or array close
                    i = skipToNextElement(json, i)
                }
            } else {
                // Primitive or string - skip to next comma or array close
                i = skipToNextElement(json, i)
            }
        }

        return elements
    }

    /**
     * Extracts a balanced `{...}` or `[...]` block starting at [startIdx].
     * Returns the substring including the outer delimiters, or null if not balanced.
     */
    private fun extractBalancedBlock(
        text: String,
        startIdx: Int
    ): String? {
        val openChar = text[startIdx]
        val closeChar = if (openChar == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false

        for (i in startIdx until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            if (ch == openChar) {
                depth++
            } else if (ch == closeChar) {
                depth--
            }

            if (depth == 0) {
                return text.substring(startIdx, i + 1)
            }
        }
        return null
    }

    /**
     * Skips past a malformed array element to the next comma or closing bracket.
     * Respects string boundaries and nested structures.
     */
    private fun skipToNextElement(
        text: String,
        startIdx: Int
    ): Int {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in startIdx until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            when (ch) {
                '{', '[' -> {
                    depth++
                }

                '}', ']' -> {
                    if (depth == 0) return i
                    depth--
                }

                ',' -> {
                    if (depth == 0) return i + 1
                }
            }
        }
        return text.length
    }

    /**
     * Create a standard error-fallback finding when AI response parsing fails.
     *
     * @param ruleId The rule identifier
     * @param content The raw AI response content; only its length is kept (model text can echo secrets)
     * @param aiModel The AI model that produced the response
     * @param tokensUsed Number of tokens consumed
     * @param prefix Description prefix for the error message
     * @param parseException Optional underlying exception message for diagnostics metadata
     * @return A single-element list with an INFO finding: a model that misbehaves is not a defect in the MR
     */
    fun parseErrorFinding(
        ruleId: String,
        content: String,
        aiModel: String,
        tokensUsed: Int,
        prefix: String = "AI analysis",
        parseException: String? = null,
    ): List<Finding> {
        val (strategy, diagnostic) = diagnoseExtraction(content)
        return listOf(
            FindingFactory.info(
                rule = ruleId,
                message = "$prefix returned unparseable response (${content.length} chars); the reply text is left out",
                suggestion = when (strategy) {
                    ExtractionStrategy.TRUNCATION_RECOVERY -> {
                        "Response was truncated (likely hit max output tokens). " +
                            "Try increasing 'reviewMaxTokens' in config or reducing diff size " +
                            "via size-policy exclude patterns."
                    }

                    ExtractionStrategy.RAW_FALLBACK -> {
                        "AI did not return JSON at all. Check if the model supports " +
                            "structured output or if the prompt is too large."
                    }

                    else -> {
                        null
                    }
                },
                metadata = buildMap {
                    put(WellKnownMetadataKeys.ERROR, PARSE_ERROR)
                    put("extractionStrategy", strategy.name)
                    put("extractionDiagnostic", diagnostic)
                    put("rawResponseLength", content.length.toString())
                    // kotlinx parse errors quote the input after "JSON input:"; keep only the reason
                    parseException?.let { put("parseException", it.substringBefore("JSON input").trim()) }
                    put(AiFindingMetadataKeys.AI_MODEL, aiModel)
                    put(AiFindingMetadataKeys.AI_TOKENS_USED, tokensUsed.toString())
                },
            ),
        )
    }
}
