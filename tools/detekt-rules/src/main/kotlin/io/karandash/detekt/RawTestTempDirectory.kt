package io.karandash.detekt

import com.intellij.psi.util.PsiTreeUtil
import dev.detekt.api.Config
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Tests get temp directories and files from `TestFiles` (`createTestTempDirectory`, `writeText`),
 * which resolve unique absolute paths on every target. Reported in test source sets (scoped by `includes` in detekt.yml):
 * `SystemTemporaryDirectory` (a relative path on the Linux CI image), `Files.createTempDirectory`/`createTempFile` and the
 * `kotlin.io.path` extensions of the same names (not cleaned up, not resolved), and a "/tmp" literal handed to `Path(...)`/`File(...)` (fake path strings in model objects are not file access).
 */
class RawTestTempDirectory(
    config: Config,
) : Rule(
        config,
        "Raw temp directory in a test: use TestFiles.createTestTempDirectory (no SystemTemporaryDirectory, createTemp*, \"/tmp\")."
    ) {
    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        if (expression is KtNameReferenceExpression &&
            expression.getReferencedName() in RAW_NAMES &&
            PsiTreeUtil.getParentOfType(expression, KtImportDirective::class.java) == null
        ) {
            report(expression)
        }
    }

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        if (expression.entries.any { TMP.containsMatchIn(it.text) } && isPathOrFileArgument(expression)) report(expression)
    }

    private fun isPathOrFileArgument(expression: KtStringTemplateExpression): Boolean {
        val call = PsiTreeUtil.getParentOfType(expression, KtCallExpression::class.java) ?: return false
        return call.calleeExpression?.text in FS_CONSTRUCTORS && expression.parent?.parent?.parent == call
    }

    private companion object {
        val FS_CONSTRUCTORS = setOf("Path", "File")
        val RAW_NAMES = setOf("SystemTemporaryDirectory", "createTempDirectory", "createTempFile")
        val TMP = Regex("""(^|[^A-Za-z0-9_.])/tmp($|[/"])""")
    }
}
