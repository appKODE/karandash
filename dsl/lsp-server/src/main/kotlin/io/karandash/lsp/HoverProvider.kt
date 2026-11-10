package io.karandash.lsp

import io.karandash.config.schema.ConfigSections
import io.karandash.lsp.HoverMarkdownFormatter.formatConfigProperty
import kotlinx.serialization.Serializable
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Provides hover documentation for Karandash DSL symbols.
 *
 * For each module, the provider first checks [io.karandash.api.Module.hoverDocumentation];
 * if that returns null, it falls back to the static maps in [ModuleDocRegistry].
 *
 * Context parsing is delegated to [DslContextResolver].
 * Dynamic block doc building is delegated to [RegistryDocBuilder].
 */
class HoverProvider(
    private val metadata: CachedRegistryMetadata,
) {
    /** Get hover documentation at a specific position in the document. */
    fun getHover(
        documentText: String,
        line: Int,
        character: Int
    ): Hover? {
        val lines = documentText.split("\n")
        if (line >= lines.size) return null

        val currentLine = lines[line]
        val symbol = extractSymbolAtPosition(currentLine, character) ?: return null

        val isBlockDeclaration = currentLine.substring(symbol.endColumn).trimStart().startsWith("{")
        val context = DslContextResolver.determineContext(documentText, line, isBlockDeclaration)
        val documentation = getDocumentation(symbol, context) ?: return null

        return Hover(
            MarkupContent(MarkupKind.MARKDOWN, documentation),
            Range(Position(line, symbol.startColumn), Position(line, symbol.endColumn)),
        )
    }

    private fun extractSymbolAtPosition(
        line: String,
        character: Int
    ): Symbol? {
        if (character > line.length) return null

        var start = character
        var end = character
        while (start > 0 && isIdentifierChar(line[start - 1])) start--
        while (end < line.length && isIdentifierChar(line[end])) end++

        if (start == end) return null
        return Symbol(line.substring(start, end), start, end)
    }

    private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun getDocumentation(
        symbol: Symbol,
        context: HoverContext
    ): String? =
        when (context) {
            HoverContext.TopLevel -> getTopLevelBlockDocs(symbol.text)
            HoverContext.RulesBlock -> getValidatorDocs(symbol.text)
            HoverContext.PlatformsBlock -> getProviderDocs(symbol.text)
            HoverContext.ReportersBlock -> getReporterDocs(symbol.text)
            HoverContext.PoliciesBlock -> getPolicyPropertyDocs(symbol.text)
            is HoverContext.ValidatorBlock -> getValidatorPropertyDocs(context.validatorName, symbol.text)
        }

    private fun getPolicyPropertyDocs(propertyName: String): String? = ModuleDocRegistry.POLICY_PROPERTY_DOCS[propertyName]

    private fun getTopLevelBlockDocs(blockName: String): String? =
        when (blockName) {
            ConfigSections.RULES -> RegistryDocBuilder.buildRulesBlockDoc(metadata)
            ConfigSections.PLATFORMS -> RegistryDocBuilder.buildProvidersBlockDoc(metadata)
            else -> ModuleDocRegistry.TOP_LEVEL_BLOCK_DOCS[blockName]
        }

    private fun getValidatorDocs(validatorName: String): String? {
        val fallback = ModuleDocRegistry.FALLBACK_VALIDATOR_DOCS[validatorName]
        val generated = metadata.moduleHoverDocs[validatorName] ?: return fallback
        return mergeWithFallbackExample(generated, fallback)
    }

    private fun getReporterDocs(name: String): String? = metadata.reporterHoverDocs[name]

    private fun getProviderDocs(name: String): String? = metadata.providerHoverDocs[name]

    private fun getValidatorPropertyDocs(
        validatorName: String,
        propertyName: String
    ): String? {
        val prop = metadata.ruleConfigProperties[validatorName]?.find { it.name == propertyName }
        if (prop != null) return formatConfigProperty(prop)
        return ModuleDocRegistry.FALLBACK_PROPERTY_DOCS[propertyName]
    }

    @Serializable
    private data class Symbol(
        val text: String,
        val startColumn: Int,
        val endColumn: Int
    )
}

/** Represents the context where the hover occurs. */
sealed class HoverContext {
    data object TopLevel : HoverContext()

    data object RulesBlock : HoverContext()

    data object PlatformsBlock : HoverContext()

    data object ReportersBlock : HoverContext()

    data object PoliciesBlock : HoverContext()

    @Serializable
    data class ValidatorBlock(
        val validatorName: String
    ) : HoverContext()
}

/**
 * A module's own hover text lists every key and default but has no sample, so the hand-written `## Example`
 * section of the bundled fallback docs (when there is one) is appended instead of being shadowed.
 */
internal fun mergeWithFallbackExample(
    generated: String,
    fallback: String?,
): String {
    val example = fallback?.substringAfter("\n## Example", "")?.takeIf { it.isNotEmpty() } ?: return generated
    if ("## Example" in generated) return generated
    return generated + "\n\n## Example" + example.trimEnd()
}
