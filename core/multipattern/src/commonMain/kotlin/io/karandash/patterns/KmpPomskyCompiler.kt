package io.karandash.patterns

import ru.kode.pomskykt.Expr
import ru.kode.pomskykt.diagnose.Severity
import ru.kode.pomskykt.options.CompileOptions
import ru.kode.pomskykt.options.RegexFlavor

/**
 * Pomsky compiler backed by the `pomsky-kt` KMP library.
 *
 * Compiles Pomsky DSL expressions directly via [Expr.parseAndCompile]
 * with the Java regex flavor.
 */
internal class KmpPomskyCompiler : PomskyCompiler {
    override fun compile(expression: String): PomskyResult {
        val (regex, diagnostics, _) = Expr.parseAndCompile(
            expression,
            CompileOptions(flavor = RegexFlavor.Java),
        )
        val warnings = diagnostics
            .filter { it.severity == Severity.Warning }
            .map { it.msg }
        val error = diagnostics
            .firstOrNull { it.severity == Severity.Error }
            ?.let { diag -> if (diag.help != null) "${diag.msg} (${diag.help})" else diag.msg }
        return PomskyResult(
            regex = regex,
            error = error,
            warnings = warnings,
        )
    }
}
