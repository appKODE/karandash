package io.karandash.lsp

import com.strumenta.antlrkotlin.runtime.BitSet
import org.antlr.v4.kotlinruntime.ANTLRErrorListener
import org.antlr.v4.kotlinruntime.Parser
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.Recognizer
import org.antlr.v4.kotlinruntime.Token
import org.antlr.v4.kotlinruntime.atn.ATNConfigSet
import org.antlr.v4.kotlinruntime.dfa.DFA
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

internal const val DIAGNOSTIC_SOURCE = "karandash"

internal fun createDiagnostic(
    token: Token,
    message: String,
    severity: DiagnosticSeverity
): Diagnostic {
    // ANTLR lines are 1-based, LSP lines are 0-based
    val line = token.line - 1
    val col = token.charPositionInLine
    val length = token.text?.length ?: 1

    return Diagnostic(
        Range(
            Position(line, col),
            Position(line, col + length)
        ),
        message,
        severity,
        DIAGNOSTIC_SOURCE
    )
}

/**
 * ANTLR error listener that collects errors as LSP Diagnostics.
 */
internal class CollectingErrorListener : ANTLRErrorListener {
    val errors = mutableListOf<Diagnostic>()

    override fun syntaxError(
        recognizer: Recognizer<*, *>,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException?
    ) {
        // ANTLR lines are 1-based, LSP is 0-based
        val lspLine = line - 1
        val length = if (offendingSymbol is Token) {
            offendingSymbol.text?.length ?: 1
        } else {
            1
        }

        errors.add(
            Diagnostic(
                Range(
                    Position(lspLine, charPositionInLine),
                    Position(lspLine, charPositionInLine + length)
                ),
                msg,
                DiagnosticSeverity.Error,
                DIAGNOSTIC_SOURCE
            )
        )
    }

    override fun reportAmbiguity(
        recognizer: Parser,
        dfa: DFA,
        startIndex: Int,
        stopIndex: Int,
        exact: Boolean,
        ambigAlts: BitSet,
        configs: ATNConfigSet
    ) {
        // Not needed for LSP diagnostics
    }

    override fun reportAttemptingFullContext(
        recognizer: Parser,
        dfa: DFA,
        startIndex: Int,
        stopIndex: Int,
        conflictingAlts: BitSet,
        configs: ATNConfigSet
    ) {
        // Not needed for LSP diagnostics
    }

    override fun reportContextSensitivity(
        recognizer: Parser,
        dfa: DFA,
        startIndex: Int,
        stopIndex: Int,
        prediction: Int,
        configs: ATNConfigSet
    ) {
        // Not needed for LSP diagnostics
    }
}
