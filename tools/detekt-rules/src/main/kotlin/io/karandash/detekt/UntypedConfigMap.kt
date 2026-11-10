package io.karandash.detekt

import dev.detekt.api.Config
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtUserType

/**
 * `Map<String, Any?>` (or any `…Map<String, Any?>` type / call) outside a parser boundary: config data flows as
 * `JsonElement` (docs/development/conventions.md "Config & Data Layer"). A `// parser-boundary: <reason>` comment on the line, the 2 lines
 * above or the line below allows it; detekt.yml limits the rule to commonMain via `includes`.
 */
class UntypedConfigMap(
    config: Config,
) : Rule(
        config,
        "Map<String, Any?> in commonMain: use JsonElement, or mark a parser boundary with // parser-boundary: <reason>.",
    ) {
    override fun visitUserType(type: KtUserType) {
        super.visitUserType(type)
        check(type, type.referencedName, type.typeArguments)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        check(expression, expression.calleeExpression?.text, expression.typeArgumentList?.arguments.orEmpty())
    }

    private fun check(
        element: KtElement,
        name: String?,
        arguments: List<KtTypeProjection>,
    ) {
        if (name?.endsWith("Map") != true) return
        if (arguments.map { it.text.filterNot(Char::isWhitespace) } != UNTYPED) return
        if (element.hasMarkerComment("parser-boundary:", before = 2, after = 1)) return
        report(element)
    }

    private companion object {
        val UNTYPED = listOf("String", "Any?")
    }
}
