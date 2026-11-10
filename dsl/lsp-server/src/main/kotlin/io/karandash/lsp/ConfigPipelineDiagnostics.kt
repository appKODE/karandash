package io.karandash.lsp

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.config.ConfigCheck
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.net.URI

/**
 * Runs a syntactically valid document through `karandash config validate` ([ConfigCheck]) with the editor buffer
 * as the config file's content: the same `extends:` chain, `{ file: }` references, interpolation, module schemas
 * and pattern checks, with the same message text. Each message is placed on the line of its key path.
 */
class ConfigPipelineDiagnostics(
    private val bootstrap: PluginBootstrap,
) {
    /** Diagnostics for [text], the buffer of the document at [uri] (`null` or not a file: checked as `config.kts`). */
    fun diagnose(
        text: String,
        uri: String? = null,
    ): List<Diagnostic> =
        runBlocking {
            // bridge-boundary: LSP4J handlers are synchronous; config loading is suspend
            ConfigCheck(bootstrap).check(pathOf(uri), readFile = { text }).messages.map { message ->
                val line = lineOf(text, message.keyPath)
                val severity = if (message.severity == ConfigCheck.Severity.ERROR) DiagnosticSeverity.Error else DiagnosticSeverity.Warning
                Diagnostic(Range(Position(line, 0), Position(line, 0)), message.text, severity, DIAGNOSTIC_SOURCE)
            }
        }

    private fun pathOf(uri: String?): Path = uri?.takeIf { it.startsWith(FILE_SCHEME) }?.let { Path(URI(it).path) } ?: Path(UNTITLED)

    // limit: each key segment searched from the line of the previous one; no source map from the parsers
    private fun lineOf(
        text: String,
        keyPath: List<String>,
    ): Int {
        val lines = text.lines()
        return keyPath.fold(0) { from, segment ->
            (from until lines.size).firstOrNull { segment in lines[it] } ?: from
        }
    }

    private companion object {
        const val FILE_SCHEME = "file:"
        const val UNTITLED = "config.kts"
    }
}
