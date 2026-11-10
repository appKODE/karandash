package io.karandash.detekt

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause

/** True when a `// <marker>` comment sits on a line in [element]'s line - [before] .. line + [after]. */
internal fun PsiElement.hasMarkerComment(
    marker: String,
    before: Int,
    after: Int,
): Boolean {
    val text = containingFile.text
    val line = lineOf(text, textOffset)
    return PsiTreeUtil.collectElementsOfType(containingFile, PsiComment::class.java).any {
        it.isMarker(marker) && lineOf(text, it.textOffset) in (line - before)..(line + after)
    }
}

private fun lineOf(
    text: String,
    offset: Int,
): Int = (0 until offset).count { text[it] == '\n' }

/** True when a `// <marker>` comment sits anywhere inside this element. */
internal fun PsiElement.containsMarkerComment(marker: String): Boolean =
    PsiTreeUtil.collectElementsOfType(this, PsiComment::class.java).any { it.isMarker(marker) }

private fun PsiComment.isMarker(marker: String): Boolean = text.split("//").drop(1).any { it.trimStart().startsWith(marker) }

/** The caught type's simple name, with a `kotlin.` / `java.lang.` qualifier dropped. */
internal val KtCatchClause.caughtType: String?
    get() =
        catchParameter
            ?.typeReference
            ?.text
            ?.removePrefix("kotlin.")
            ?.removePrefix("java.lang.")

/** The file's absolute path with `/` separators (detekt 2 names a PSI file by its bare name; the path is the virtual file's). */
internal val PsiElement.filePath: String
    get() = (containingFile.virtualFile?.path ?: containingFile.name).replace('\\', '/')

/** Reports [element] with [message] (the rule description by default). */
internal fun Rule.report(
    element: PsiElement,
    message: String = description,
) = report(Finding(Entity.from(element), message))

/** Reports every call whose callee is [callee] and for which [violation] returns a message. */
abstract class ForbiddenCallRule(
    config: Config,
    private val callee: String,
    description: String,
) : Rule(config, description) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != callee) return
        violation(expression)?.let { report(expression, it) }
    }

    /** The finding message for [call], or null when this call is allowed. */
    protected open fun violation(call: KtCallExpression): String? = description
}
