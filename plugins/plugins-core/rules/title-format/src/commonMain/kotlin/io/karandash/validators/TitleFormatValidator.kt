package io.karandash.validators

import io.karandash.api.errors.KarandashError
import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Severity
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.patterns.PatternFix
import io.karandash.utils.BoundedCache
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.suggestion.ContextAwareSuggestionGenerator
import io.karandash.utils.validation.RuleLengthValidator

/**
 * @param regexCache Optional cache of successfully compiled user patterns keyed by pattern string, shared
 *   across validator instances. Invalid patterns are never cached, so their finding is reported on every call.
 */
class TitleFormatValidator(
    private val config: TitleFormatConfig,
    private val regexCache: BoundedCache<String, Regex>? = null,
    patterns: PatternAwareCompiler? = null,
) {
    private companion object {
        val DEFAULT_SUGGESTION = TitleFormatText.SUGGESTION.en
        val DEFAULT_EXPLANATION = TitleFormatText.EXPLANATION.en
        val TICKET_REGEX = Regex("[A-Z]+-\\d+")
    }

    // Effective pattern: explicit `pattern` takes precedence; fall back to structured; then default.
    private val effectivePattern: String? = config.pattern
        ?: TitleFormatConfig.buildStructuredPattern(config)

    // Compile once at construction time; null if the pattern is invalid
    private val compiledRegex: Regex?
    private val regexError: Finding?

    init {
        val errors = mutableListOf<Finding>()
        compiledRegex = effectivePattern?.let { pattern ->
            // Only bare regexes are cached: `@name` etc. resolve per run, so another run's definition is never reused.
            val cache = regexCache?.takeUnless { RegexHelper.looksLikePatternSyntax(pattern) }
            cache?.get(pattern)
                ?: RegexHelper.tryCompile(patterns, pattern, TitleFormatRuleModule.RULE_ID, errors)?.also {
                    cache?.put(pattern, it)
                }
        }
        regexError = errors.firstOrNull()
    }

    fun validate(mr: MergeRequest): List<Finding> {
        if (effectivePattern != null && compiledRegex == null) {
            return listOfNotNull(regexError)
        }

        return buildList {
            if (compiledRegex != null && !compiledRegex.matches(mr.title)) {
                val rawPattern = config.pattern ?: effectivePattern ?: ""
                val fix = PatternFix.of(rawPattern, mr.title, subject = "title")
                add(
                    FindingFactory
                        .error(
                            rule = TitleFormatRuleModule.RULE_ID,
                            message = "MR title does not match required pattern.",
                            suggestion = fix.suggestion,
                            explanation = DEFAULT_EXPLANATION,
                            metadata = fix.metadata() + fix.format?.let { mapOf(WellKnownMetadataKeys.PATTERN to it) }.orEmpty(),
                            // Only a correction that keeps the author's text is safe to apply; examples are suggestions only.
                            fixSuggestions = listOfNotNull(fix.value?.takeIf { fix.deterministic }?.let { FixSuggestion.SetTitle(it) }),
                        ).copy(errorCode = KarandashError.TITLE_FORMAT_MISMATCH.code),
                )
            }

            addAll(
                RuleLengthValidator
                    .checkLength(
                        text = mr.title,
                        minLength = config.minLength,
                        maxLength = config.maxLength,
                        ruleId = TitleFormatRuleModule.RULE_ID,
                        fieldName = "MR title",
                        suggestion = DEFAULT_SUGGESTION,
                        explanation = DEFAULT_EXPLANATION,
                        maxSeverity = Severity.WARNING,
                    ).map { it.copy(errorCode = KarandashError.TITLE_LENGTH_VIOLATION.code) },
            )

            if (config.requireTicket && !TICKET_REGEX.containsMatchIn(mr.title)) {
                add(
                    FindingFactory.error(
                        rule = TitleFormatRuleModule.RULE_ID,
                        message = "MR title has no ticket reference.",
                        suggestion = ContextAwareSuggestionGenerator.suggestTitleWithTicket(mr.title, mr.sourceBranch),
                        explanation = DEFAULT_EXPLANATION
                    )
                )
            }

            config.subject?.let { subjectConfig ->
                if (subjectConfig.noPeriod && mr.title.endsWith(".")) {
                    add(
                        FindingFactory.error(
                            rule = TitleFormatRuleModule.RULE_ID,
                            message = "MR title must not end with a period.",
                            suggestion = "Remove the trailing period from the title.",
                            explanation = DEFAULT_EXPLANATION
                        )
                    )
                }
            }
        }
    }
}
