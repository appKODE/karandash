package io.karandash.plugins.ai.analyzers.reviewcomments

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.suspendCatching
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AbstractAiAnalyzer
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.PromptSections
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.tokensFor
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * AI-powered code review comment generator. Analyzes merge request diffs
 * and generates actionable review comments with file locations.
 */
class ReviewCommentsAnalyzerModule : AbstractAiAnalyzer(AiRuleIds.REVIEW_COMMENTS, "reviewComments") {
    override val id: String = "ai-review-comments-analyzer"
    override val name: String = "AI Review Comments"
    override val resultSerializer: KSerializer<*> = ReviewCommentsResult.serializer()
    override val defaultEnabled: Boolean = true
    override val estimatedTokens: Int = 3000
    override val defaultPrompt: String = DEFAULT_SYSTEM_PROMPT
    override val explanation: String =
        "Generates AI-powered review comments highlighting potential issues, best practice violations, and improvement suggestions."
    override val needsScopeDetection: Boolean = true

    /** The ids the data-driven context enrichers turn into ticket, CI, symbol and hotspot blocks. */
    override val supportedEnrichment: List<String> =
        listOf(
            EnrichmentKeys.JIRA_TICKETS,
            EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA,
            EnrichmentKeys.GITHUB_ISSUES,
            EnrichmentKeys.GITLAB_ISSUES,
            EnrichmentKeys.CI_FAILED_JOBS,
            EnrichmentKeys.CI_COVERAGE_GAPS,
            EnrichmentKeys.SYMBOLS_CHANGED,
            EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY,
        )
    override val responseFormatSuffix: String = RESPONSE_FORMAT_SUFFIX
    override val supportsSemiFormalReasoning: Boolean = true

    private val json = KarandashJson.IgnoreUnknown

    override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
        val aiConfig = AiRuleConfig.fromPluginConfig(context.config)
        val tokens = aiConfig.tokensFor(featureKey)

        val contextBlock = buildContextBlock(context)
        val prompt = buildPrompt(context)
        val reviewPrompt = if (aiConfig.includeFixSuggestions) {
            buildSuggestionPrompt(aiConfig.fixSuggestionMinSeverity)
        } else {
            DEFAULT_SYSTEM_PROMPT
        }
        val basePrompt = if (AiPluginConfig.fromPluginConfig(context.config).review.bugChecklist) {
            reviewPrompt + "\n\n" + BugChecklist.forChanges(context.mr.changes)
        } else {
            reviewPrompt
        }
        val systemPrompt = AiRuleAnalyzerUtils.effectivePrompt(
            basePrompt,
            context.extraInstructions,
            context.resolvedPrompt,
            responseFormatSuffix,
        )

        val maxTokens = AiRuleAnalyzerUtils.resolveMaxResponseTokens(
            configuredMax = tokens.maxTokens,
            defaultMax = tokens.defaultMaxTokens,
            systemPrompt = systemPrompt,
            userPrompt = "$contextBlock\n\n$prompt",
            tokens = aiConfig.analyzerTokens,
        )

        val response = context.aiService.complete(
            AiCompletionRequest(
                prompt = prompt,
                contextBlocks = listOf(contextBlock),
                systemPrompt = systemPrompt,
                model = context.model,
                maxTokens = maxTokens,
                temperature = tokens.temperature,
            )
        )

        return parseResponse(
            content = response.content,
            aiModel = response.model,
            tokensUsed = response.totalTokens,
            mr = context.mr,
            aiConfig = aiConfig,
            logger = context.logger,
        )
    }

    /** MR metadata and instructions: the part of the user turn that stays the same while the diff changes. */
    internal fun buildContextBlock(context: AiAnalyzerContext): String {
        val mr = context.mr
        val sb = StringBuilder()

        sb.append(PromptSections.mergeRequest(mr))

        sb.appendLine("## Instructions")
        sb.appendLine("Report only defects you are sure of in the diffs below; return an empty comments list when there are none.")
        return sb.toString().trimEnd()
    }

    /** The changed files and their diffs, sent after [buildContextBlock]. */
    internal fun buildPrompt(context: AiAnalyzerContext): String {
        val mr = context.mr
        val sb = StringBuilder()
        sb.appendLine("## Changed Files (${mr.changes.size} total)")
        for (change in mr.changes) {
            sb.appendLine("- ${change.path} [${change.changeType}] (+${change.additions}/-${change.deletions})")
        }
        sb.appendLine()

        sb.appendLine("## File Diffs")
        sb.append(context.diffContext.preparedDiff)

        return sb.toString()
    }

    internal fun parseResponse(
        content: String,
        aiModel: String,
        tokensUsed: Int,
        mr: MergeRequest? = null,
        aiConfig: AiRuleConfig = AiRuleConfig(),
        logger: Logger = NoOpLogger,
    ): List<Finding> {
        val minSeverity = Severity.fromString(aiConfig.fixSuggestionMinSeverity, default = Severity.WARNING)
        val validFilePaths = mr?.changes?.map { it.path }?.toSet()
        val addedLinesByFile = mr?.changes?.associate { change ->
            change.path to AiRuleAnalyzerUtils.extractAddedLines(change.diffContent)
        }

        return try {
            val jsonStr = AiRuleAnalyzerUtils.extractJson(content)
            val result = json.decodeFromString<ReviewCommentsResult>(jsonStr)
            commentListToFindings(
                comments = result.comments,
                validFilePaths = validFilePaths,
                addedLinesByFile = addedLinesByFile,
                minSeverity = minSeverity,
                aiModel = aiModel,
                logger = logger,
            )
        } catch (ignored: Exception) {
            // boundary: parsing untrusted LLM output
            ignored.rethrowCancellation()
            // Per-comment recovery: extract individual comment objects and parse each independently
            val recovered = recoverIndividualComments(content)
            if (recovered.isNotEmpty()) {
                commentListToFindings(
                    comments = recovered,
                    validFilePaths = validFilePaths,
                    addedLinesByFile = addedLinesByFile,
                    minSeverity = minSeverity,
                    aiModel = aiModel,
                    logger = logger,
                )
            } else {
                AiRuleAnalyzerUtils.parseErrorFinding(
                    ruleId = analyzerId,
                    content = content,
                    aiModel = aiModel,
                    tokensUsed = tokensUsed,
                    prefix = "AI review comment generation",
                )
            }
        }
    }

    /**
     * Attempts to recover individual [ReviewComment] objects from a response whose
     * top-level JSON failed to parse. Extracts each element from the `"comments"` array
     * independently, skipping malformed entries.
     */
    private fun recoverIndividualComments(content: String): List<ReviewComment> {
        val jsonStr = suspendCatching { AiRuleAnalyzerUtils.extractJson(content) }.getOrNull() ?: return emptyList()
        val elements = AiRuleAnalyzerUtils.extractJsonArrayElements(jsonStr, "comments")
        return elements.mapNotNull { element ->
            suspendCatching { json.decodeFromString<ReviewComment>(element) }.getOrNull()
        }
    }

    /**
     * Converts a list of parsed [ReviewComment] objects into [Finding] instances,
     * applying validation (line filtering, file path check, snap-to-added-line)
     * and optionally splitting code suggestions into separate findings.
     */
    private fun logDrop(
        logger: Logger,
        comment: ReviewComment,
        reason: String,
        aiModel: String,
    ) {
        logger.debug(
            "$LINE_DIAG_PREFIX drop [$analyzerId] file=${comment.file} " +
                "llm_line=${comment.line} reason=$reason model=$aiModel",
        )
    }

    private fun commentListToFindings(
        comments: List<ReviewComment>,
        validFilePaths: Set<String>?,
        addedLinesByFile: Map<String, Set<Int>>?,
        minSeverity: Severity,
        aiModel: String,
        logger: Logger,
    ): List<Finding> =
        // No cap here: `maxComments` caps the whole MR after the cross-chunk dedupe (ReviewCommentsCap in rules:ai).
        comments
            .mapNotNull { raw ->
                val file = AiRuleAnalyzerUtils.normalizeDiffPath(raw.file, validFilePaths)
                val comment = if (file == raw.file) raw else raw.copy(file = file)
                if (comment.line <= 0) {
                    logDrop(logger, comment, reason = "non_positive", aiModel = aiModel)
                    return@mapNotNull null
                }

                if (validFilePaths != null && comment.file !in validFilePaths) {
                    logDrop(logger, comment, reason = "file_not_in_mr", aiModel = aiModel)
                    return@mapNotNull null
                }

                val addedLines = addedLinesByFile?.get(comment.file)
                val hasAddedLines = addedLines != null && addedLines.isNotEmpty()
                val snappedLine = if (hasAddedLines) {
                    AiRuleAnalyzerUtils.snapToAddedLine(comment.line, checkNotNull(addedLines))
                } else {
                    comment.line
                }
                // Out-of-diff line: keep the comment at file level (flagged) instead of dropping it.
                val demoted = snappedLine == null
                val effectiveLine = snappedLine ?: comment.line
                if (demoted) logDrop(logger, comment, reason = "snap_failed_demoted_to_file", aiModel = aiModel)

                val effectiveEndLine = comment.endLine?.takeUnless { demoted }?.let { endLine ->
                    if (hasAddedLines) {
                        AiRuleAnalyzerUtils.snapToAddedLine(endLine, checkNotNull(addedLines))
                    } else {
                        endLine
                    }
                }

                logger.debug(
                    "$LINE_DIAG_PREFIX keep [$analyzerId] file=${comment.file} " +
                        "llm_line=${comment.line} snapped_line=$effectiveLine " +
                        "snap_delta=${abs(effectiveLine - comment.line)} " +
                        "llm_endLine=${comment.endLine} snapped_endLine=$effectiveEndLine " +
                        "added_lines_known=$hasAddedLines model=$aiModel",
                )

                val category = ReviewCategory.normalize(comment.category)
                // A QUESTION depends on code the model could not see: always INFO, never inline as a defect.
                val severity = if (category == ReviewCategory.QUESTION) Severity.INFO else Severity.fromString(comment.severity)

                val includeSuggestion = !comment.suggestedCode.isNullOrBlank() &&
                    severity.ordinal <= minSeverity.ordinal

                val location = Location.FileRef(
                    path = comment.file,
                    line = effectiveLine.takeUnless { demoted },
                    endLine = effectiveEndLine,
                )

                val reviewMetadata = buildMap {
                    put(AiFindingMetadataKeys.CATEGORY, category)
                    put(AiFindingMetadataKeys.CONFIDENCE, comment.confidence.toString())
                    comment.evidence?.takeIf { it.isNotBlank() }?.let { put(AiFindingMetadataKeys.EVIDENCE, it) }
                    if (demoted) {
                        put(AiFindingMetadataKeys.LINE_DEMOTED, "true")
                        put(AiFindingMetadataKeys.LLM_LINE, comment.line.toString())
                    }
                }

                val reviewFinding = Finding(
                    severity = severity,
                    message = comment.message,
                    rule = analyzerId,
                    location = location,
                    suggestion = comment.fix?.takeIf { it.isNotBlank() },
                    metadata = reviewMetadata,
                )

                if (includeSuggestion && !demoted && !comment.existingCode.isNullOrBlank()) {
                    val suggestionMetadata = buildMap {
                        put(AiFindingMetadataKeys.CATEGORY, category)
                        put(AiFindingMetadataKeys.CONFIDENCE, comment.confidence.toString())
                        put(AiFindingMetadataKeys.EXISTING_CODE, comment.existingCode)
                        put(AiFindingMetadataKeys.SUGGESTED_CODE, comment.suggestedCode)
                    }

                    val suggestionFinding = Finding(
                        severity = severity,
                        message = "Code suggestion ($category)",
                        rule = CODE_SUGGESTIONS_RULE_ID,
                        location = location,
                        metadata = suggestionMetadata,
                    )

                    listOf(reviewFinding, suggestionFinding)
                } else {
                    listOf(reviewFinding)
                }
            }.flatten()

    companion object {
        internal const val CODE_SUGGESTIONS_RULE_ID = AiRuleIds.CODE_SUGGESTIONS

        /** Telemetry prefix for line-number decisions. Grep this in debug logs when diagnosing wrong-line inline comments. */
        private const val LINE_DIAG_PREFIX = "[ai-line-diagnostics]"

        private val RESPONSE_FORMAT_SUFFIX = """
            |{
            |  "comments": [
            |    {
            |      "file": "src/path/to/file.kt",
            |      "line": 42,
            |      "endLine": 44,
            |      "category": "${ReviewCategory.ALL.joinToString("|")}",
            |      "severity": "error|warning|info",
            |      "confidence": 0.85,
            |      "message": "the problem only, at most 2 sentences: what is wrong and why",
            |      "evidence": "the exact added line the finding is about",
            |      "fix": "(optional) one imperative sentence: how to fix it",
            |      "existingCode": "(optional) original code being replaced",
            |      "suggestedCode": "(optional) replacement code that fixes the issue"
            |    }
            |  ]
            |}
        """.trimMargin()

        private val DEFAULT_SYSTEM_PROMPT: String by lazy { reviewPrompt(severityInstruction = null) }

        /**
         * Builds the system prompt variant that requests fix suggestions.
         * The [minSeverity] parameter controls which severity levels get suggestions,
         * instructing the LLM to skip generating `suggestedCode` for lower severities
         * to save tokens.
         */
        private fun buildSuggestionPrompt(minSeverity: String): String {
            val severityInstruction = when (minSeverity.lowercase()) {
                "error" -> {
                    "Only include suggestedCode for findings with severity \"error\". " +
                        "Do NOT generate suggestedCode for \"warning\" or \"info\" findings."
                }

                "info" -> {
                    "Include suggestedCode for findings of any severity when a concrete fix is possible."
                }

                else -> {
                    "Only include suggestedCode for findings with severity \"error\" or \"warning\". " +
                        "Do NOT generate suggestedCode for \"info\" findings."
                }
            }
            return reviewPrompt(severityInstruction)
        }

        /** one review prompt; a non-null [severityInstruction] adds the fix-suggestion delta. */
        private fun reviewPrompt(severityInstruction: String?): String {
            val suggest = severityInstruction != null
            val task = if (suggest) "comments with concrete fix suggestions." else "comments."
            val anchor = if (suggest) "starts" else "occurs"
            return listOfNotNull(
                "You are a code review assistant. Analyze the provided merge request diffs and generate actionable code review $task",
                FOCUS,
                FIELDS.replace(ANCHOR, anchor) + if (suggest) SUGGESTION_FIELDS else "",
                LINE_RULES + if (suggest) SUGGESTION_LINE_RULE else "",
                severityInstruction,
                GENERAL_RULES + if (suggest) SUGGESTION_GENERAL_RULES else "",
            ).joinToString("\n\n")
        }

        private const val ANCHOR = "{anchor}"

        private val FOCUS = """
Report only defects that change behaviour:
- BUG: wrong result or crash (inverted or always-true/false condition, equals/== that never matches, all{}/any{} on a possibly empty collection, wrong time zone such as LocalDateTime.now() without a zone, off-by-one, wrong operand)
- SEC: injection, authentication or authorization gaps, secrets or personal data exposed
- PERF: only a measurable cost on a realistic input
- RACE: only with a concrete interleaving you can name
- LEAK: a resource or memory that is never released
- DATA: data loss or corruption
Do NOT report naming, formatting, readability, refactoring ideas, missing docs, missing tests, or anything a linter catches.

Read removed (`-`) lines as carefully as added ones. For every removed or moved block ask: what check, guard, enable flag or behaviour did it provide, and what happens now without it or in its new position? For a removed guard, feature/enable check, null or permission check, or a call moved above or out of an `if`, decide what it protected and report a BUG if that case is now unprotected.
        """.trimIndent()

        private val FIELDS = """
For each comment, provide:
- file: exact file path from the diff
- line: NEW-FILE line number where the issue $ANCHOR (must be > 0). See "Line number rules" below.
- endLine: (optional) new-file line number where the issue ends; omit unless the problem spans multiple lines.
- ${ReviewCategory.PROMPT_LINE}
- severity: one of [error, warning, info] — error: crash, data loss, security hole or wrong result on a common path; warning: wrong result on an edge case; info: a question or a minor risk
- confidence: how sure you are that the described behavior really happens in this code (0.0 to 1.0), not how severe it is. Use 0.9+ for a defect you can see directly in the diff, even a minor one, 0.5-0.8 for likely issues, below 0.5 for uncertain observations; express impact through severity
- message: the problem only, at most 2 sentences: what is wrong and why it matters
- evidence: the exact added (`+`) line the finding is about, copied verbatim without the `L<n>(+) ` prefix
- fix: (optional) one imperative sentence telling how to fix it, with the exact name or value where possible
        """.trimIndent()

        private val SUGGESTION_FIELDS = "\n" + """
- existingCode: (optional, required when suggestedCode is provided) the exact existing code lines being replaced, copied verbatim from the diff
- suggestedCode: (optional) the replacement code that fixes the issue. Include ONLY the replacement lines, not the surrounding context.
        """.trimIndent()

        private val LINE_RULES = """
Line number rules (critical — inline comments that miss the actual line are useless):
- Every added line in the diff is prefixed with `L<n>(+) ` where <n> is its NEW-FILE line number. Use <n> verbatim as the `line` value; anchor on a code line, never on a closing brace, blank or comment-only line. Do NOT count added rows yourself; do NOT use old-file line numbers; do NOT use line numbers relative to the hunk.
- Only anchor comments on lines that start with `+` in the diff, never on context lines (prefixed with a space) or removed lines (prefixed with `-`). A defect caused by removed lines goes on the nearest added line of the same hunk, and the message names the removed code.
- Prefer single-line comments: set `endLine` equal to `line`, or omit it entirely. Only use a larger `endLine` when the whole range is genuinely the issue (e.g. a misaligned block of arguments), and never exceed 5 lines between `line` and `endLine`.
        """.trimIndent()

        private const val SUGGESTION_LINE_RULE =
            "\n- `existingCode` must be a verbatim substring of what appears on those `line`..`endLine` rows (without the `L<n>(+) ` prefix)."

        private val GENERAL_RULES = """
General rules:
- Only comment on defects introduced by this change
- Your input is a partial diff. Never claim an identifier is undefined, unused, missing or removed: it may be defined elsewhere. If a claim depends on code you cannot see, make it a QUESTION, not a BUG
- A change the MR title or description states as intended is not a defect unless the code contradicts that intent
- Every finding names the failing line and the input or state that triggers the failure
- Never ask the author to "verify", "ensure" or "check" something; if you cannot show the defect from the code you were given, omit it
- One finding per root cause, highest impact first
- An empty comments list is a correct and common answer
        """.trimIndent()

        private val SUGGESTION_GENERAL_RULES = "\n" + """
- Always provide existingCode when providing suggestedCode
- suggestedCode must contain the full replacement for lines from line to endLine
- Omit suggestedCode when no concrete fix is possible
        """.trimIndent()
    }
}

@Serializable
internal data class ReviewCommentsResult(
    val comments: List<ReviewComment>,
)

@Serializable
internal data class ReviewComment(
    val file: String,
    val line: Int,
    val endLine: Int? = null,
    val category: String,
    val severity: String,
    val message: String,
    val evidence: String? = null,
    val fix: String? = null,
    val confidence: Double = DEFAULT_CONFIDENCE,
    val existingCode: String? = null,
    val suggestedCode: String? = null,
) {
    companion object {
        const val DEFAULT_CONFIDENCE = 0.5
    }
}
