package io.karandash.lsp

import io.karandash.config.schema.ConfigSections
import io.karandash.parser.generated.KarandashDslLexer
import io.karandash.parser.generated.KarandashDslParser
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

/**
 * Provides LSP diagnostics by parsing Karandash DSL documents
 * and performing syntax and semantic validation.
 *
 * Module metadata (known rule IDs, provider IDs, reporter IDs) is
 * resolved from the [ModuleRegistry] rather than hardcoded lists. [pipeline] adds the errors and schema
 * warnings `validate` reports for the same document.
 */
class DiagnosticProvider(
    metadata: CachedRegistryMetadata,
    private val pipeline: ConfigPipelineDiagnostics? = null,
) {
    private val rulesValidator = RulesBlockValidator(metadata)
    private val reportersPlatformsValidator = ReportersPlatformsValidator(metadata)

    companion object {
        private val VALID_TOP_LEVEL_BLOCKS = ConfigSections.all
    }

    /**
     * Compute diagnostics for the given document text; [uri] locates it for the pipeline check (`extends:`, `{ file: }`).
     */
    fun computeDiagnostics(
        text: String,
        uri: String? = null,
    ): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()

        // Phase 1: Syntax errors from ANTLR
        val charStream = CharStreams.fromString(text)
        val lexer = KarandashDslLexer(charStream)
        lexer.removeErrorListeners()
        val lexerErrors = CollectingErrorListener()
        lexer.addErrorListener(lexerErrors)

        val tokens = CommonTokenStream(lexer)
        val parser = KarandashDslParser(tokens)
        parser.removeErrorListeners()
        val parserErrors = CollectingErrorListener()
        parser.addErrorListener(parserErrors)

        val tree = parser.config()

        diagnostics.addAll(lexerErrors.errors)
        diagnostics.addAll(parserErrors.errors)

        // Phase 2: Semantic validation (only if parsing succeeded without errors)
        if (diagnostics.isEmpty()) {
            diagnostics.addAll(validateSemantics(tree))
            pipeline?.let { diagnostics.addAll(it.diagnose(text, uri)) }
        }

        return diagnostics
    }

    /**
     * Validate semantic rules on a successfully parsed tree.
     */
    private fun validateSemantics(tree: KarandashDslParser.ConfigContext): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()

        for (block in tree.topLevelItem().mapNotNull { it.topLevelBlock() }) {
            val blockName = block.IDENTIFIER().text
            val blockToken = block.IDENTIFIER().symbol

            if (blockName !in VALID_TOP_LEVEL_BLOCKS) {
                diagnostics.add(
                    createDiagnostic(
                        blockToken,
                        "Unknown top-level block '$blockName'. Valid blocks: ${VALID_TOP_LEVEL_BLOCKS.sorted().joinToString(", ")}",
                        DiagnosticSeverity.Error
                    )
                )
                continue
            }

            // Validate statements within each block type
            when (blockName) {
                ConfigSections.RULES -> diagnostics.addAll(rulesValidator.validateRulesBlock(block))
                ConfigSections.REPORTERS -> diagnostics.addAll(reportersPlatformsValidator.validateReportersBlock(block))
                ConfigSections.PLATFORMS -> diagnostics.addAll(reportersPlatformsValidator.validatePlatformsBlock(block))
            }
        }

        // Check for duplicate top-level blocks
        val seen = mutableSetOf<String>()
        for (block in tree.topLevelItem().mapNotNull { it.topLevelBlock() }) {
            val name = block.IDENTIFIER().text
            if (name in seen) {
                diagnostics.add(
                    createDiagnostic(
                        block.IDENTIFIER().symbol,
                        "Duplicate top-level block '$name'. Each block should appear at most once.",
                        DiagnosticSeverity.Warning
                    )
                )
            }
            seen.add(name)
        }

        return diagnostics
    }
}
