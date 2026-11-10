package io.karandash.validators

import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.withText
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.patterns.PatternFix
import io.karandash.utils.ConventionalTypes
import io.karandash.utils.finding.CommitFindingGroup
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.finding.FindingFingerprint
import io.karandash.utils.mr.conventionalCommitPattern
import io.karandash.utils.mr.shortSha
import io.karandash.utils.suggestion.ContextAwareSuggestionGenerator

class CommitMessagesValidator(
    private val config: CommitMessagesConfig,
    patterns: PatternAwareCompiler? = null,
) {
    companion object {
        const val FORMAT_CONVENTIONAL = "conventional"

        /** No format check (`pattern`, length and the other checks still apply). */
        const val FORMAT_ANY = "any"

        /** Format given by `pattern` only. */
        const val FORMAT_CUSTOM = "custom"

        private val KNOWN_FORMATS = setOf(FORMAT_CONVENTIONAL, FORMAT_ANY, FORMAT_CUSTOM)
        internal val DEFAULT_SUGGESTION = CommitMessagesText.SUGGESTION.en
        private const val SUBJECT = "commit message"
        internal val DEFAULT_EXPLANATION = CommitMessagesText.EXPLANATION.en
    }

    private val conventionalPattern: Regex

    private val compiledPattern: Regex?
    private val patternError: Finding?

    // Effective allowed types: structured type.allowed > conventionalTypes > defaults
    private val effectiveConventionalTypes: List<String> =
        config.type?.allowed ?: config.conventionalTypes ?: ConventionalTypes.DEFAULT

    private val conventionalPatternStr = conventionalCommitPattern(effectiveConventionalTypes)

    init {
        conventionalPattern = RegexHelper.compileOrNull(conventionalPatternStr)
            ?: error("Failed to compile conventional commit pattern from escaped types")

        val errors = mutableListOf<Finding>()
        compiledPattern = config.pattern?.let { RegexHelper.tryCompile(patterns, it, CommitMessagesRuleModule.RULE_ID, errors) }
        patternError = errors.firstOrNull()
    }

    fun validate(mr: MergeRequest): List<Finding> {
        val effectiveMaxLength = config.effectiveMaxLength()
        // Determine whether to apply conventional format check:
        // explicit `format: conventional` OR structured `type` config without a raw pattern override
        val applyConventional = config.format == FORMAT_CONVENTIONAL ||
            (config.type != null && config.pattern == null)
        val firstLines = mr.commits.map { it.sha.shortSha() to it.message.lines().first() }

        return buildList {
            if (config.pattern != null && mr.commits.isNotEmpty()) {
                if (compiledPattern == null) {
                    patternError?.let { add(it) }
                } else {
                    patternMismatch(config.pattern, firstLines.filterNot { compiledPattern.matches(it.second) })?.let { add(it) }
                }
            }

            if (config.format != null && config.format !in KNOWN_FORMATS && mr.commits.isNotEmpty()) {
                add(
                    FindingFactory.error(
                        rule = CommitMessagesRuleModule.RULE_ID,
                        message = "Unknown commit format: ${config.format}",
                        suggestion = DEFAULT_SUGGESTION,
                        explanation = DEFAULT_EXPLANATION
                    )
                )
            }

            if (applyConventional) {
                conventionalMismatch(firstLines.filterNot { conventionalPattern.matches(it.second) })?.let { add(it) }
            }

            config.minLength?.let { min ->
                val short = firstLines.filter { it.second.length < min }.map { (sha, line) ->
                    sha to FindingFactory.error(
                        rule = CommitMessagesRuleModule.RULE_ID,
                        message = "Commit $sha subject is ${line.length} chars (minimum $min).",
                        suggestion = "Expand it to at least $min characters.",
                        explanation = DEFAULT_EXPLANATION,
                    )
                }
                addAll(CommitFindingGroup.collapse(short, "have a subject shorter than $min chars"))
            }

            effectiveMaxLength?.let { max ->
                val long = firstLines.filter { it.second.length > max }.map { (sha, line) ->
                    sha to FindingFactory.warn(
                        rule = CommitMessagesRuleModule.RULE_ID,
                        message = "Commit $sha subject is ${line.length} chars (limit $max).",
                        suggestion = "Shorten it; move details to the body.",
                        explanation = DEFAULT_EXPLANATION,
                    )
                }
                addAll(CommitFindingGroup.collapse(long, "have a subject over $max chars"))
            }
        }
    }

    /** One finding for every commit whose first line misses [pattern]; the expected-pattern text is stated once. */
    private fun patternMismatch(
        pattern: String,
        failing: List<Pair<String, String>>,
    ): Finding? {
        if (failing.isEmpty()) return null
        // The fix stays a suggestion, never a FixSuggestion: autofix cannot rewrite pushed commits (N/A by design).
        val fix = PatternFix.of(pattern, failing.first().second, subject = SUBJECT)
        return FindingFactory
            .error(
                rule = CommitMessagesRuleModule.RULE_ID,
                message = CommitMessagesText.NO_PATTERN_MATCH.en,
                suggestion = fix.suggestion,
                explanation = DEFAULT_EXPLANATION,
                metadata = fix.metadata() + fix.format?.let { mapOf(WellKnownMetadataKeys.PATTERN to it) }.orEmpty(),
            ).naming(CommitMessagesText.NO_PATTERN_MATCH, failing)
    }

    /** One finding for every commit whose first line is not a conventional commit. */
    private fun conventionalMismatch(failing: List<Pair<String, String>>): Finding? {
        if (failing.isEmpty()) return null
        val first = failing.first().second
        val fix = PatternFix.of(conventionalPatternStr, first, subject = SUBJECT)
        // An exact correction beats the keyword guess; otherwise the type is inferred from the message words.
        val suggestion = if (fix.deterministic) {
            fix.suggestion
        } else {
            ContextAwareSuggestionGenerator.suggestConventionalCommit(first, effectiveConventionalTypes)
        }
        return FindingFactory
            .error(
                rule = CommitMessagesRuleModule.RULE_ID,
                message = CommitMessagesText.NOT_CONVENTIONAL.en,
                suggestion = suggestion,
                explanation = DEFAULT_EXPLANATION,
                metadata = fix.metadata(),
            ).naming(CommitMessagesText.NOT_CONVENTIONAL, failing)
    }

    /**
     * Names the failing commits in the message, where a configured `explanation:` cannot replace them. The
     * fingerprint is built from the [text] template, so a new bad commit does not change it (and re-post the thread).
     */
    private fun Finding.naming(
        text: CommitMessagesText,
        failing: List<Pair<String, String>>,
    ): Finding =
        copy(
            fingerprint = FindingFingerprint.generate(rule, null, text.en),
            attachments = if (failing.size > CommitFindingGroup.SHOWN) {
                listOf(
                    Attachment(
                        filename = "All ${failing.size} commits",
                        content = failing.joinToString("\n") { "- ${it.first} ${it.second}" },
                    ),
                )
            } else {
                attachments
            },
        ).withText(message = text.of(CommitFindingGroup.shortList(failing.map { it.first })))
}
