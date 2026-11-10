package io.karandash.lsp

import io.karandash.parser.generated.KarandashDslParser
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

/** Semantic validation of the `reporters { }` and `platforms { }` blocks. */
internal class ReportersPlatformsValidator(
    private val metadata: CachedRegistryMetadata,
) {
    private val providerIds: Set<String> get() = metadata.providerIds

    private val blockReporterIds: Set<String> get() = metadata.blockReporterIds

    private val propertyReporterIds: Set<String> get() = metadata.propertyReporterIds

    fun validateReportersBlock(block: KarandashDslParser.TopLevelBlockContext): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val knownPropertyReporters = propertyReporterIds
        val knownBlockReporters = blockReporterIds

        for (stmt in block.statement()) {
            val assignment = stmt.assignment()
            if (assignment != null) {
                val name = assignment.IDENTIFIER().text
                if (name !in knownPropertyReporters) {
                    diagnostics.add(
                        createDiagnostic(
                            assignment.IDENTIFIER().symbol,
                            "Unknown reporter property '$name'. Valid properties: " +
                                knownPropertyReporters.sorted().joinToString(", "),
                            DiagnosticSeverity.Warning
                        )
                    )
                }
                continue
            }

            val funcCall = stmt.functionCall() ?: continue
            val name = funcCall.IDENTIFIER().text
            if (name !in knownBlockReporters) {
                diagnostics.add(
                    createDiagnostic(
                        funcCall.IDENTIFIER().symbol,
                        "Unknown reporter block '$name'. Valid blocks: ${knownBlockReporters.sorted().joinToString(", ")}",
                        DiagnosticSeverity.Warning
                    )
                )
            }
        }

        return diagnostics
    }

    fun validatePlatformsBlock(block: KarandashDslParser.TopLevelBlockContext): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val knownProviders = providerIds

        for (stmt in block.statement()) {
            val assignment = stmt.assignment()
            if (assignment != null) {
                val name = assignment.IDENTIFIER().text
                if (name !in knownProviders) {
                    diagnostics.add(
                        createDiagnostic(
                            assignment.IDENTIFIER().symbol,
                            "Unknown provider '$name'. Valid providers: ${knownProviders.sorted().joinToString(", ")}",
                            DiagnosticSeverity.Warning
                        )
                    )
                }
                continue
            }

            val funcCall = stmt.functionCall() ?: continue
            val name = funcCall.IDENTIFIER().text
            if (name !in knownProviders) {
                diagnostics.add(
                    createDiagnostic(
                        funcCall.IDENTIFIER().symbol,
                        "Unknown provider '$name'. Valid providers: ${knownProviders.sorted().joinToString(", ")}",
                        DiagnosticSeverity.Warning
                    )
                )
            }
        }

        return diagnostics
    }
}
