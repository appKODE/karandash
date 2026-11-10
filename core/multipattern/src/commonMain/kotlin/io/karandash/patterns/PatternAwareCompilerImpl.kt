package io.karandash.patterns

import io.karandash.helpers.RegexHelper
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.PatternAwareCompiler

/**
 * Bridges [PatternResolver] to [PatternAwareCompiler] for injection into
 * [io.karandash.helpers.RegexHelper].
 *
 * On success, returns the compiled [Regex]. On failure, appends an ERROR
 * [Finding] and returns `null`.
 */
class PatternAwareCompilerImpl(
    private val resolver: PatternResolver,
) : PatternAwareCompiler {
    override fun compile(
        pattern: String,
        ruleId: String,
        findings: MutableList<Finding>,
    ): Regex? =
        when (val result = resolver.resolve(pattern)) {
            is PatternCompilationResult.Success -> {
                RegexHelper.warnIfAmbiguous(result.regex.pattern, ruleId, findings)
                result.regex
            }

            is PatternCompilationResult.Error -> {
                findings += Finding(
                    severity = Severity.ERROR,
                    message = result.message,
                    rule = ruleId,
                )
                null
            }
        }
}
