package io.karandash.lsp

import io.karandash.parser.generated.KarandashDslParser
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

/** Semantic validation of the `rules { }` block. */
internal class RulesBlockValidator(
    private val metadata: CachedRegistryMetadata,
) {
    private val ruleIds: Set<String> get() = metadata.ruleIds

    fun validateRulesBlock(block: KarandashDslParser.TopLevelBlockContext): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val seenRules = mutableSetOf<String>()
        val knownRules = ruleIds

        for (stmt in block.statement()) {
            val funcCall = stmt.functionCall() ?: continue
            val name = funcCall.IDENTIFIER().text
            val token = funcCall.IDENTIFIER().symbol

            if (name !in knownRules) {
                diagnostics.add(
                    createDiagnostic(
                        token,
                        "Unknown rule '$name'. Valid rules: ${knownRules.sorted().joinToString(", ")}",
                        DiagnosticSeverity.Error
                    )
                )
                continue
            }

            if (name in seenRules) {
                diagnostics.add(
                    createDiagnostic(
                        token,
                        "Duplicate rule '$name'. Each rule should appear at most once.",
                        DiagnosticSeverity.Warning
                    )
                )
            }
            seenRules.add(name)

            // Validate required properties within specific rules
            diagnostics.addAll(validateRuleProperties(name, funcCall))
        }

        return diagnostics
    }

    private fun validateRuleProperties(
        ruleName: String,
        funcCall: KarandashDslParser.FunctionCallContext
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val funcBlock = funcCall.block() ?: return diagnostics
        val assignments = funcBlock
            .statement()
            .mapNotNull { it.assignment() }
            .associate { it.IDENTIFIER().text to it }

        // Build expected types from cached configSchema
        val schema = metadata.ruleConfigProperties[ruleName]
        if (schema != null) {
            // Check required properties
            for (prop in schema) {
                if (prop.required && prop.name !in assignments) {
                    diagnostics.add(
                        createDiagnostic(
                            funcCall.IDENTIFIER().symbol,
                            "Missing required property '${prop.name}' in $ruleName rule.",
                            DiagnosticSeverity.Error,
                        )
                    )
                }
            }

            // Build expected-type map for type checking
            val expectedTypes = schema
                .mapNotNull { prop ->
                    val propType = when (prop.type) {
                        io.karandash.api.config.ConfigPropertyType.STRING -> PropertyType.STRING
                        io.karandash.api.config.ConfigPropertyType.INT -> PropertyType.NUMBER
                        io.karandash.api.config.ConfigPropertyType.BOOLEAN -> PropertyType.BOOLEAN
                        io.karandash.api.config.ConfigPropertyType.STRING_LIST -> PropertyType.LIST
                        io.karandash.api.config.ConfigPropertyType.OBJECT_LIST -> PropertyType.LIST
                        else -> null
                    }
                    if (propType != null) prop.name to propType else null
                }.toMap()

            if (expectedTypes.isNotEmpty()) {
                validatePropertyTypes(assignments, expectedTypes, ruleName, diagnostics)
            }
        }

        return diagnostics
    }

    private fun validatePropertyTypes(
        assignments: Map<String, KarandashDslParser.AssignmentContext>,
        expectedTypes: Map<String, PropertyType>,
        ruleName: String,
        diagnostics: MutableList<Diagnostic>
    ) {
        for ((propName, assignment) in assignments) {
            val expectedType = expectedTypes[propName]
            if (expectedType != null) {
                val expr = assignment.expression()
                val actualType = inferExpressionType(expr)
                if (actualType != null && actualType != expectedType) {
                    diagnostics.add(
                        createDiagnostic(
                            assignment.IDENTIFIER().symbol,
                            "Type mismatch for '$propName' in $ruleName: expected ${expectedType.displayName}, got ${actualType.displayName}.",
                            DiagnosticSeverity.Error
                        )
                    )
                }
            }
        }
    }

    private fun inferExpressionType(expr: KarandashDslParser.ExpressionContext): PropertyType? =
        when (expr) {
            is KarandashDslParser.LiteralExprContext -> {
                val literal = expr.literal()
                when {
                    literal.STRING() != null -> PropertyType.STRING
                    literal.NUMBER() != null -> PropertyType.NUMBER
                    literal.BOOLEAN() != null -> PropertyType.BOOLEAN
                    literal.listLiteral() != null -> PropertyType.LIST
                    else -> null
                }
            }

            is KarandashDslParser.FunctionCallExpressionContext -> {
                if (expr.functionCallExpr().IDENTIFIER().text == "listOf") {
                    PropertyType.LIST
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }

    private enum class PropertyType(
        val displayName: String
    ) {
        STRING("string"),
        NUMBER("number"),
        BOOLEAN("boolean"),
        LIST("list")
    }
}
