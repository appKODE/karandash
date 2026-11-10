package io.karandash.plugins.ai.core.config

import io.karandash.helpers.RegexHelper
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.utils.GlobMatcher
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Rule composition algebra: create synthetic findings from combinations of existing rule results.
 *
 * Compositions are evaluated at the end of validation. Each [CompositionRule] specifies a boolean
 * expression over rule outcomes; when the expression evaluates to true, a synthetic [Finding] is
 * emitted with the rule ID `composed:<name>`.
 *
 * Supported expression syntax:
 * - `rule_failed(id)` - true when any finding exists for a rule containing [id]
 * - `rule_passed(id)` - true when no finding exists for a rule containing [id]
 * - `high(id)`, `medium(id)`, `low(id)` - a rule containing [id] has an ERROR, WARNING or INFO finding
 * - `keyword_match(field, 'regex')` - `title`, `description`, `source_branch` or `target_branch` matches
 * - `file_match('glob')` - a changed file matches the glob
 * - `expr1 && expr2` - logical AND
 * - `expr1 || expr2` - logical OR
 * - `not(expr)`, `!expr` - logical NOT
 */
object RuleComposer {
    private const val COMPOSED_PREFIX = "composed:"
    private const val NOT_PREFIX = "not("
    private const val BANG = "!"
    private const val AND_OP = " && "
    private const val OR_OP = " || "
    private const val RULE_FAILED = "rule_failed"
    private const val RULE_PASSED = "rule_passed"
    private const val KEYWORD_MATCH = "keyword_match"
    private const val FILE_MATCH = "file_match"
    private val SEVERITY_FUNCTIONS = mapOf("high" to Severity.ERROR, "medium" to Severity.WARNING, "low" to Severity.INFO)

    /**
     * Evaluates each [CompositionRule] against [findings] and returns synthetic findings
     * for every composition whose expression evaluates to true.
     *
     * @param findings The findings produced by normal rule evaluation.
     * @param compositions The composition rules to evaluate.
     * @param mr The merge request, for `keyword_match` and `file_match`; both are false without it.
     * @return Synthetic findings; empty if no composition expressions match.
     */
    fun evaluate(
        findings: List<Finding>,
        compositions: List<CompositionRule>,
        mr: MergeRequest? = null,
    ): List<Finding> {
        val ruleFindings = findings.groupBy { it.rule }
        return compositions.mapNotNull { comp ->
            if (evaluateExpression(comp.expression, ruleFindings, mr)) {
                Finding(
                    severity = Severity.fromString(comp.severity, default = Severity.WARNING),
                    message = comp.message,
                    rule = "$COMPOSED_PREFIX${comp.name}",
                )
            } else {
                null
            }
        }
    }

    /**
     * Evaluates a boolean expression string against the given rule findings map.
     * Exposed as `internal` for unit-testability.
     */
    internal fun evaluateExpression(
        expression: String,
        ruleFindings: Map<String, List<Finding>>,
        mr: MergeRequest? = null,
    ): Boolean {
        val trimmed = expression.trim()

        if (trimmed.startsWith(NOT_PREFIX) && trimmed.endsWith(")")) {
            return !evaluateExpression(trimmed.substring(NOT_PREFIX.length, trimmed.length - 1), ruleFindings, mr)
        }

        if (trimmed.contains(AND_OP)) {
            return trimmed.split(AND_OP).all { evaluateExpression(it, ruleFindings, mr) }
        }

        if (trimmed.contains(OR_OP)) {
            return trimmed.split(OR_OP).any { evaluateExpression(it, ruleFindings, mr) }
        }

        if (trimmed.startsWith(BANG)) return !evaluateExpression(trimmed.removePrefix(BANG), ruleFindings, mr)

        val name = trimmed.substringBefore("(").trim()
        if (!trimmed.endsWith(")") || name == trimmed) return false
        val args = trimmed
            .substringAfter("(")
            .removeSuffix(")")
            .split(",", limit = 2)
            .map { it.trim().trim('\'', '"') }
        val ruleId = args.first()
        val severity = SEVERITY_FUNCTIONS[name]
        return when {
            name == RULE_FAILED -> ruleFindings.keys.any { it.contains(ruleId) }
            name == RULE_PASSED -> ruleFindings.keys.none { it.contains(ruleId) }
            severity != null -> ruleFindings.any { (rule, list) -> rule.contains(ruleId) && list.any { it.severity == severity } }
            name == KEYWORD_MATCH -> mr != null && keywordMatch(mr, args)
            name == FILE_MATCH -> mr != null && mr.changes.any { GlobMatcher.matches(it.path, ruleId) }
            else -> false
        }
    }

    private fun keywordMatch(
        mr: MergeRequest,
        args: List<String>,
    ): Boolean {
        val text = when (args.first()) {
            "title" -> mr.title
            "description" -> mr.description.orEmpty()
            "source_branch", "branch" -> mr.sourceBranch
            "target_branch" -> mr.targetBranch
            else -> return false
        }
        val pattern = args.getOrNull(1) ?: return false
        return RegexHelper.compileOrNull(pattern)?.containsMatchIn(text) == true
    }
}

/**
 * A named composition rule that emits a synthetic finding when its expression is true.
 *
 * @param name Unique name for this composition; the resulting finding rule ID is `composed:<name>`.
 * @param expression Boolean expression over rule outcomes (see [RuleComposer] for syntax); `when` is an alias.
 * @param severity Severity of the emitted finding (case-insensitive; defaults to `warning`).
 * @param message Human-readable message for the emitted finding.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CompositionRule(
    val name: String,
    @JsonNames("when") val expression: String,
    val severity: String = "warning",
    val message: String,
)
