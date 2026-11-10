package io.karandash.detekt

import dev.detekt.api.Config
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/**
 * Unqualified `println(` / `print(` and `System.out` / `System.err` (docs/development/conventions.md "No println()"): use Logger or Mordant.
 * The process-level output sinks themselves are listed as `excludes` in detekt.yml.
 */
class RawConsoleOutput(
    config: Config,
) : Rule(config, "Raw println/print/System.out|err outside the sanctioned output sinks: use Logger or Mordant.") {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val qualified = (expression.parent as? KtQualifiedExpression)?.selectorExpression == expression
        if (!qualified && expression.calleeExpression?.text in PRINTS) report(expression)
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        val receiver = expression.receiverExpression.text
        if ((receiver == "System" || receiver.endsWith(".System")) && expression.selectorExpression?.text in STREAMS) {
            report(expression)
        }
    }

    private companion object {
        val PRINTS = setOf("println", "print")
        val STREAMS = setOf("out", "err")
    }
}
