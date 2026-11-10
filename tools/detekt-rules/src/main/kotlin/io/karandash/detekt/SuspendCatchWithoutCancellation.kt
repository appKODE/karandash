package io.karandash.detekt

import dev.detekt.api.Config
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * `CancellationException` IS an `IllegalStateException`, so catching `IllegalStateException` or `RuntimeException`
 * in a `suspend fun` swallows cancellation unless the clause calls `rethrowCancellation()` / names
 * `CancellationException`, or an earlier clause of the same `try` catches `CancellationException`.
 */
class SuspendCatchWithoutCancellation(
    config: Config,
) : Rule(config, "IllegalStateException/RuntimeException caught in a suspend fun without rethrowCancellation.") {
    override fun visitCatchSection(catchClause: KtCatchClause) {
        super.visitCatchSection(catchClause)
        if (catchClause.caughtType !in CANCELLATION_SUPERTYPES) return
        val function = catchClause.getStrictParentOfType<KtNamedFunction>() ?: return
        if (!function.hasModifier(KtTokens.SUSPEND_KEYWORD) || handlesCancellation(catchClause)) return
        report(catchClause)
    }

    private fun handlesCancellation(catchClause: KtCatchClause): Boolean {
        val text = catchClause.text
        if ("rethrowCancellation" in text || "CancellationException" in text) return true
        val siblings = (catchClause.parent as? KtTryExpression)?.catchClauses.orEmpty()
        return siblings.takeWhile { it != catchClause }.any { it.caughtType?.endsWith("CancellationException") == true }
    }

    private companion object {
        val CANCELLATION_SUPERTYPES = setOf("IllegalStateException", "RuntimeException")
    }
}
