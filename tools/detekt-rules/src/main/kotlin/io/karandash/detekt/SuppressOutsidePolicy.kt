package io.karandash.detekt

import com.intellij.psi.util.PsiTreeUtil
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Every `@Suppress` / `@file:Suppress` id must be in `allowedIds`, the docs/development/conventions.md "@Suppress Policy" permitted
 * categories: fix the code instead of suppressing.
 *
 * The rule cannot silence itself: `@Suppress("SuppressOutsidePolicy")` (or `all`, `karandash`, …) on a declaration
 * would otherwise hide its own finding, so a finding's `ktElement` is a detached expression with no annotated parent
 * (detekt applies `@Suppress` by walking the reported element's parents). A file-level suppression makes detekt skip
 * the whole file before any rule runs; detekt's own `style>ForbiddenSuppress` (exempt from suppression) closes that
 * gap in detekt.yml.
 */
class SuppressOutsidePolicy(
    config: Config,
) : Rule(config, "@Suppress id outside the docs/development/conventions.md @Suppress Policy permitted categories: fix, do not suppress.") {
    private val allowedIds: Set<String> = config.valueOrDefault("allowedIds", emptyList<String>()).toSet()

    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        if (annotationEntry.shortName?.asString() !in SUPPRESS_ANNOTATIONS) return
        val ids = PsiTreeUtil.collectElementsOfType(annotationEntry, KtLiteralStringTemplateEntry::class.java).map { it.text }
        ids.filterNot { it in allowedIds }.forEach {
            val entity = Entity.from(annotationEntry)
            val detached = KtPsiFactory(annotationEntry.project).createExpression("0")
            report(
                Finding(
                    Entity(entity.signature, entity.location, detached),
                    "@Suppress(\"$it\") is not a docs/development/conventions.md permitted category: fix, do not suppress.",
                ),
            )
        }
    }

    private companion object {
        val SUPPRESS_ANNOTATIONS = setOf("Suppress", "SuppressWarnings")
    }
}
