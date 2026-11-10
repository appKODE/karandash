package io.karandash.detekt

import dev.detekt.api.Config
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * `Regex(...)` and `.toRegex()` take a pattern written in the source: a string literal, optionally with
 * `${Regex.escape(...)}` or constant (`${'$'}`) pieces (docs/development/conventions.md "Security Conventions"). Any other pattern may come from config, so it goes
 * through `RegexHelper` / `SafeRegex` and their ReDoS guards; the guards themselves are the `excludes` in detekt.yml.
 */
class DynamicRegex(
    config: Config,
) : Rule(config, "Regex on a non-literal pattern: use RegexHelper.tryCompile / compileOrNull or SafeRegex.") {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val pattern = when (expression.calleeExpression?.text) {
            "Regex" -> regexConstructorPattern(expression)
            "toRegex" -> (expression.parent as? KtQualifiedExpression)?.receiverExpression
            else -> return
        }
        if (pattern != null && !pattern.isSourcePattern()) report(expression)
    }

    private fun regexConstructorPattern(call: KtCallExpression): KtExpression? {
        val args = call.valueArguments
        val named = args.firstOrNull { it.getArgumentName()?.asName?.asString() == "pattern" }
        return (named ?: args.firstOrNull { !it.isNamed() })?.getArgumentExpression()
    }

    private fun KtExpression.isSourcePattern(): Boolean =
        when (this) {
            is KtStringTemplateExpression -> {
                entries.all { it.isSourceEntry() }
            }

            is KtParenthesizedExpression -> {
                expression?.isSourcePattern() == true
            }

            is KtBinaryExpression -> {
                operationToken == KtTokens.PLUS && left?.isSourcePattern() == true && right?.isSourcePattern() == true
            }

            else -> {
                isRegexEscape()
            }
        }

    private fun KtStringTemplateEntry.isSourceEntry(): Boolean =
        when (this) {
            is KtLiteralStringTemplateEntry, is KtEscapeStringTemplateEntry -> true
            is KtStringTemplateEntryWithExpression -> expression.let { it is KtConstantExpression || it?.isRegexEscape() == true }
            else -> false
        }

    private fun KtExpression.isRegexEscape(): Boolean =
        this is KtDotQualifiedExpression &&
            receiverExpression.text == "Regex" &&
            (selectorExpression as? KtCallExpression)?.calleeExpression?.text == "escape"
}
