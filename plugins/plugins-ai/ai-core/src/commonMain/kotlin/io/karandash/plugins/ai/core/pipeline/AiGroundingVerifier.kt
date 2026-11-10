package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.SecretRedactor
import io.karandash.plugins.ai.core.isAiFinding
import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/**
 * Bi-directional AI grounding: deterministic verification of AI-generated claims.
 *
 * After AI produces findings, this verifier checks each finding against the actual
 * MR diff content. Findings that make claims contradicted by the code are
 * dropped (with `failureMode: downgrade`: INFO with a [REFUTED] prefix).
 *
 * Verification checks:
 * 1. **Symbol existence** - if the finding message references a symbol/identifier,
 *    does that symbol appear anywhere in the file's changed content?
 * 2. **Code snippet** - if the finding contains a backtick-quoted code snippet
 *    (containing operators, parens, dots, etc.), does that snippet appear in the diff?
 *
 * Only AI findings (`Finding.isAiFinding()`) are subject to grounding.
 * Non-AI findings pass through unchanged.
 *
 * The same class runs the second-opinion pass (`ai.grounding.verifier`, [secondOpinion]); it merged the former
 * `FindingVerifier`, and that name stays as a typealias.
 *
 * @param config Controls which verification checks are enabled.
 * @param aiService Service of the [secondOpinion] call; null makes that pass keep every finding.
 * @param model Model of the [secondOpinion] call.
 * @param budget The run's token budget (`ai.maxTokenBudget`) the [secondOpinion] call counts against.
 * @param permits The run's call permits (`ai.concurrency`) the [secondOpinion] call holds one of.
 */
class AiGroundingVerifier(
    private val config: GroundingConfig = GroundingConfig(),
    private val logger: Logger = NoOpLogger,
    private val aiService: AiService? = null,
    private val model: String? = null,
    private val budget: AiTokenBudget? = null,
    private val permits: Semaphore? = null,
) {
    /** The second-opinion verifier only, as the former `FindingVerifier(aiService, model, budget, permits)`. */
    constructor(
        aiService: AiService,
        model: String,
        budget: AiTokenBudget? = null,
        permits: Semaphore? = null,
    ) : this(GroundingConfig(), NoOpLogger, aiService, model, budget, permits)

    /**
     * Verifies AI-generated findings against the actual MR diff content.
     *
     * @param findings All validation findings (AI and non-AI).
     * @param mr The merge request containing the diff to verify against.
     * @return A [GroundingResult] with verified findings and refutation counts.
     */
    fun verify(
        findings: List<Finding>,
        mr: MergeRequest,
    ): GroundingResult {
        if (!config.enabled) {
            return GroundingResult(
                findings = findings,
                refutedCount = 0,
                totalAiFindings = findings.count { it.isAiFinding() },
            )
        }
        logger.debug(
            "Grounding: checking ${findings.count { it.isAiFinding() }} AI finding(s) " +
                "(symbols=${config.checkSymbolExistence}, snippets=${config.checkCodeSnippets}, window=${config.windowLines})",
        )
        val result = LocalGrounding(config, mr, logger).run(findings)
        return result
    }

    /**
     * Second-opinion pass (`ai.grounding.verifier`): one call to a (usually cheaper) model that reads each
     * WARNING-or-worse AI finding on a file next to the diff of its file and answers `valid` or `invalid`, the verdict
     * before the reason so the reason cannot talk the model into it. Only `invalid` drops a finding; order is kept.
     *
     * Fails open: no service, a provider error, a timeout, an unreadable reply or a prompt over [MAX_PROMPT_CHARS]
     * keeps every finding. Diffs go through [SecretRedactor] before they are sent, as for the review itself. The call
     * counts against [budget] (a budget that cannot cover the prompt skips the call) and holds one of [permits].
     */
    suspend fun secondOpinion(
        findings: List<Finding>,
        mr: MergeRequest,
    ): List<Finding> {
        val service = aiService ?: return findings
        val verifierModel = model ?: return findings
        val checkedAt = findings.indices.filter { isChecked(findings[it]) }
        if (checkedAt.isEmpty()) return findings
        val prompt = buildVerifierPrompt(checkedAt.map { findings[it] }, SecretRedactor.redact(mr))
        if (prompt.length > MAX_PROMPT_CHARS) return findings
        if (budget?.canSpend(prompt.length / CHARS_PER_TOKEN + VERIFIER_MAX_TOKENS) == false) return findings
        val invalid = askInvalid(service, verifierModel, prompt, checkedAt.size) ?: return findings
        val dropped = invalid.map { checkedAt[it] }.toSet()
        return findings.filterIndexed { i, _ -> i !in dropped }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun askInvalid(
        service: AiService,
        verifierModel: String,
        prompt: String,
        count: Int,
    ): Set<Int>? =
        try {
            val request = AiCompletionRequest(
                prompt = prompt,
                systemPrompt = VERIFIER_SYSTEM_PROMPT,
                model = verifierModel,
                maxTokens = VERIFIER_MAX_TOKENS,
                temperature = VERIFIER_TEMPERATURE,
                jsonMode = true,
            )
            val reply = permits?.withPermit { service.complete(request) } ?: service.complete(request)
            budget?.recordSpend(reply.totalTokens)
            KarandashJson.Default
                .decodeFromString<VerifierReply>(AiRuleAnalyzerUtils.extractJson(reply.content))
                .verdicts
                .filter { it.verdict == INVALID && it.index in 0 until count }
                .map { it.index }
                .toSet()
        } catch (e: Exception) {
            // boundary: verifier LLM call or its untrusted reply; fail open and keep every finding
            e.rethrowCancellation()
            null
        }

    private fun buildVerifierPrompt(
        checked: List<Finding>,
        mr: MergeRequest,
    ): String {
        val paths = checked.mapNotNull { (it.location as? Location.FileRef)?.path }.toSet()
        return buildString {
            appendLine("## Findings")
            checked.forEachIndexed { i, f ->
                val loc = f.location as? Location.FileRef
                val where = loc?.let { "${it.path}:${it.line ?: "?"}" } ?: "(no file)"
                appendLine("$i. [$where] ${f.message}")
                f.explanation?.let { appendLine("   $it") }
            }
            mr.changes.filter { it.path in paths }.forEach { change ->
                appendLine()
                appendLine("## Diff of ${change.path}")
                appendLine(change.diffContent.orEmpty())
            }
        }
    }

    private fun isChecked(finding: Finding): Boolean =
        finding.isAiFinding() &&
            finding.severity != Severity.INFO &&
            finding.location is Location.FileRef

    companion object {
        /** [secondOpinion] prompts longer than this are not sent; the findings are kept (budget fail-open). */
        const val MAX_PROMPT_CHARS = 60_000
        private const val VERIFIER_MAX_TOKENS = 1500
        private const val CHARS_PER_TOKEN = 4
        private const val VERIFIER_TEMPERATURE = 0.0
        private const val INVALID = "invalid"

        private val VERIFIER_SYSTEM_PROMPT = """
You check code review findings against the diff they are about. For each numbered finding decide
whether the diff supports it. Answer "invalid" only when the diff shows the finding is wrong or
describes code that is not there; when unsure answer "valid".

Give the verdict first, then a one-line reason. Respond with ONLY valid JSON:
{"verdicts": [{"index": 0, "verdict": "valid", "reason": "..."}]}
        """.trimIndent()

        private const val MIN_SYMBOL_LENGTH = 2
        private const val MAX_IDENTIFIER_RESULTS = 5

        private val BACKTICK_REGEX = Regex("`([^`]+)`")
        private val SYMBOL_TOKEN_REGEX = Regex("[A-Za-z_][\\w.]*(\\(\\))?")
        private val ACRONYM_PLURAL_REGEX = Regex("[A-Z]{2,}s")

        private val IDENTIFIER_REGEX = Regex(
            "\\b([A-Z][a-zA-Z0-9]{2,}|[a-z][a-zA-Z0-9]*[A-Z][a-zA-Z0-9]*|[a-z_]{3,}_[a-z_]+)\\b",
        )

        private val COMMON_WORDS: Set<String> = setOf(
            "the",
            "and",
            "for",
            "not",
            "are",
            "this",
            "that",
            "with",
            "from",
            "has",
            "have",
            "been",
            "will",
            "can",
            "may",
            "should",
            "could",
            "would",
            "does",
            "did",
            "was",
            "were",
            "being",
            "which",
            "when",
            "where",
            "while",
            "also",
            "but",
            "than",
            "then",
            "into",
            "only",
            "each",
            "some",
            "other",
            "more",
            "very",
            "just",
            "about",
            "over",
            "after",
            "before",
            "between",
            "through",
            "both",
            "these",
            "those",
            "such",
            "here",
            "there",
            "most",
            "like",
            "make",
            "made",
            "many",
            "use",
            "used",
            "using",
            "check",
            "code",
            "file",
            "line",
            "change",
            "function",
            "method",
            "class",
            "error",
            "warning",
            "issue",
            "null",
            "return",
            "value",
            "type",
            "name",
            "test",
            "data",
            "missing",
            "added",
            "removed",
            "consider",
            "instead",
            "ensure",
            "avoid",
            "potential",
            "possible",
            "suggest",
            "recommend",
            "review",
            "found",
            "note",
            "warning",
            "error",
            "info",
            "todo",
        )

        /** Backtick-quoted names in a finding message: the only symbols that can refute it. */
        internal fun extractSymbols(text: String): List<String> =
            BACKTICK_REGEX
                .findAll(text)
                .map { it.groupValues[1].trim() }
                // `[]`, `[Optional]`, `!= null` are notation, not names the diff could contain
                .filter { it.length >= MIN_SYMBOL_LENGTH && SYMBOL_TOKEN_REGEX.matches(it) }
                .toList()

        /** camelCase/snake_case words of a message without backticks; prose, so a miss only downgrades. */
        internal fun extractProseSymbols(text: String): List<String> =
            IDENTIFIER_REGEX
                .findAll(text)
                .map { it.groupValues[1] }
                .filter { it.lowercase() !in COMMON_WORDS }
                // all-caps words are acronyms or category labels (BUG, API), not identifiers in the diff
                .filter { word -> word.any { it.isLowerCase() } }
                // acronym plurals (URLs, APKs, IDs) are prose too
                .filterNot { ACRONYM_PLURAL_REGEX.matches(it) }
                // one leading capital is prose (Live, Non); identifiers carry an inner capital or an underscore
                .filter { word -> word.drop(1).any { it.isUpperCase() || it == '_' } || word.first().isLowerCase() }
                .toList()
                .take(MAX_IDENTIFIER_RESULTS)

        /**
         * Extracts code snippets from backtick-quoted text.
         *
         * Only returns snippets that look like code (contain operators, parens, dots, etc.)
         * to avoid false negatives from plain-text backtick usage.
         */
        internal fun extractCodeSnippets(text: String): List<String> =
            BACKTICK_REGEX
                .findAll(text)
                .map { it.groupValues[1].trim() }
                .filter { snippet ->
                    snippet.length >= 3 &&
                        (
                            snippet.contains('.') ||
                                snippet.contains('(') ||
                                snippet.contains('=') ||
                                snippet.contains("::") ||
                                snippet.contains("->")
                        )
                }.toList()
    }
}

/**
 * Configuration for the AI grounding verifier.
 *
 * @param enabled Whether grounding verification is active. Defaults to true.
 * @param checkSymbolExistence When true, verifies that symbols referenced in the finding appear in the diff.
 * @param checkCodeSnippets When true, verifies that code snippets in the finding appear in the diff.
 * @param autoDowngradeRefuted When true (default), refuted findings are dropped (unless [failureMode] says downgrade).
 *   When false, refuted findings keep their original severity but still receive the [REFUTED] prefix.
 * @param failureMode What happens to a refuted finding: [FAILURE_MODE_DOWNGRADE] (INFO with the [REFUTED]
 *   prefix) or [FAILURE_MODE_REMOVE] (dropped). Null (or any other value) leaves it to [autoDowngradeRefuted].
 * @param verifier The optional LLM verifier pass, see [VerifierConfig].
 * @param windowLines A backticked name must be a whole token within this many lines of the anchor (or in the file's
 *   diff); a miss lowers the finding one level. Must be positive.
 * @param negatedClaims Refute an existence claim ("`foo` is undefined", "not found", "does not exist") about a name
 *   that is in the MR's added or context lines or new file text.
 * @param evidenceCheck Check the quoted evidence line: not near the anchor lowers the finding, not in the file refutes it.
 * @param miss What a soft miss does: [MISS_DEMOTE] (one level lower) or [MISS_DROP] (dropped, except BUG/SEC/DATA/LEAK).
 */
@Serializable
data class GroundingConfig(
    val enabled: Boolean = true,
    val checkSymbolExistence: Boolean = true,
    val checkCodeSnippets: Boolean = true,
    val autoDowngradeRefuted: Boolean = true,
    val failureMode: String? = null,
    val verifier: VerifierConfig = VerifierConfig(),
    val windowLines: Int = DEFAULT_WINDOW_LINES,
    val negatedClaims: Boolean = true,
    val evidenceCheck: Boolean = true,
    val miss: String = MISS_DEMOTE,
) {
    /** This config, or a [ConfigParseException] naming the bad `ai.grounding` key. */
    fun validated(): GroundingConfig {
        if (miss !in setOf(MISS_DEMOTE, MISS_DROP)) {
            throw ConfigParseException("ai.grounding.miss: '$miss' is not one of $MISS_DEMOTE, $MISS_DROP")
        }
        if (windowLines <= 0) throw ConfigParseException("ai.grounding.windowLines: $windowLines must be positive")
        return this
    }

    companion object {
        const val FAILURE_MODE_DOWNGRADE = "downgrade"
        const val FAILURE_MODE_REMOVE = "remove"
        const val MISS_DEMOTE = "demote"
        const val MISS_DROP = "drop"
        const val DEFAULT_WINDOW_LINES = 40
    }
}

/**
 * `ai.grounding.verifier`: the LLM second-opinion pass, [AiGroundingVerifier.secondOpinion], run after grounding.
 *
 * @param enabled Off by default: its effect on precision has not been measured on a live model.
 * @param model Model for the pass; null uses the main `ai.model`.
 */
@Serializable
data class VerifierConfig(
    val enabled: Boolean = false,
    val model: String? = null,
)

/**
 * Result of grounding verification.
 *
 * @param findings Verified findings, with refuted ones downgraded to INFO.
 * @param refutedCount Number of AI findings that failed at least one grounding check.
 * @param totalAiFindings Total number of AI findings subject to grounding.
 * @param records Each input finding's grounding decision, dumped as `ai/stages/grounding.json`.
 */
@Serializable
data class GroundingResult(
    val findings: List<Finding>,
    val refutedCount: Int,
    val totalAiFindings: Int,
    val records: List<StageRecord> = emptyList(),
)

/**
 * Individual verification check result.
 *
 * @param name Short identifier for the check type (e.g. "symbol-existence", "code-snippet").
 * @param passed Whether the check passed (evidence found in the diff).
 * @param evidence The symbols or snippets that were checked.
 * @param refutes Whether a failure drops the finding; a failed check that does not refute only lowers its severity.
 */
@Serializable
data class GroundingCheck(
    val name: String,
    val passed: Boolean,
    val evidence: List<String> = emptyList(),
    val refutes: Boolean = true,
)
