package io.karandash.patterns

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.kode.pomskykt.Expr
import ru.kode.pomskykt.analysis.ComplexityReport
import ru.kode.pomskykt.analysis.ComplexityScorer
import ru.kode.pomskykt.diagnose.Diagnostic
import ru.kode.pomskykt.diagnose.Severity
import ru.kode.pomskykt.options.CompileOptions
import ru.kode.pomskykt.options.RegexFlavor

/**
 * A problem in a `pomsky:` pattern of a config, found when the config itself is checked.
 *
 * @property path Where the pattern sits in the config, e.g. `rules.titleFormat.pattern` or `patterns[1]`.
 * @property message The problem; a compile error carries the expression line with carets under the bad part.
 * @property hint How to fix it, when pomsky says.
 * @property error `true` when the pattern does not compile; `false` for a lint or complexity warning.
 */
@Serializable
data class PomskyConfigIssue(
    val path: String,
    val message: String,
    val hint: String? = null,
    val error: Boolean = false,
)

/**
 * Config-time checks of every `pomsky:` string in a config (`config validate`, `doctor`): compiles it with the
 * pomsky linter on, and scores its complexity. Lint diagnostics and a [ComplexityReport.Level.HIGH] score are
 * warnings; a compile error is an error.
 */
object PomskyConfigCheck {
    private const val PREFIX = "pomsky:"
    private const val INDENT = "    "
    private val OPTIONS = CompileOptions(flavor = RegexFlavor.Java, lintEnabled = true)

    /** The issues of all `pomsky:` patterns in [config], in config order. */
    fun check(config: JsonElement): List<PomskyConfigIssue> =
        patterns(config, "").flatMap { (path, pattern) -> checkPattern(path, pattern.removePrefix(PREFIX).trim()) }

    private fun patterns(
        element: JsonElement,
        path: String,
    ): List<Pair<String, String>> =
        when (element) {
            is JsonObject -> element.entries.flatMap { (key, value) -> patterns(value, if (path.isEmpty()) key else "$path.$key") }
            is JsonArray -> element.flatMapIndexed { index, value -> patterns(value, "$path[$index]") }
            is JsonPrimitive -> if (element.isString && element.content.startsWith(PREFIX)) listOf(path to element.content) else emptyList()
            JsonNull -> emptyList()
        }

    private fun checkPattern(
        path: String,
        expression: String,
    ): List<PomskyConfigIssue> {
        val (regex, diagnostics, _) = Expr.parseAndCompile(expression, OPTIONS)
        val error = diagnostics.firstOrNull { it.severity == Severity.Error }
        if (regex == null || error != null) {
            return listOf(compileError(path, expression, error))
        }
        val warnings = diagnostics
            .filter { it.severity == Severity.Warning }
            .map { PomskyConfigIssue(path, "$path: ${it.msg}", it.help) }
        return warnings + listOfNotNull(complexity(path, expression))
    }

    private fun compileError(
        path: String,
        expression: String,
        error: Diagnostic?,
    ): PomskyConfigIssue {
        val reason = error?.msg ?: "unknown error"
        val marked = error?.let { markSpan(expression, it.span.start, it.span.end) } ?: "$INDENT$expression"
        return PomskyConfigIssue(
            path = path,
            message = "$path: pomsky pattern does not compile: $reason\n$marked",
            hint = error?.help ?: "Fix the pomsky expression at the marked position.",
            error = true,
        )
    }

    /** The line of [expression] holding [start], with `^` under [start] until [end] (at least one). */
    private fun markSpan(
        expression: String,
        start: Int,
        end: Int,
    ): String {
        val lineStart = expression.lastIndexOf('\n', start - 1) + 1
        val lineEnd = expression.indexOf('\n', start).let { if (it < 0) expression.length else it }
        val width = (minOf(end, lineEnd) - start).coerceAtLeast(1)
        return "$INDENT${expression.substring(lineStart, lineEnd)}\n$INDENT${" ".repeat(start - lineStart)}${"^".repeat(width)}"
    }

    private fun complexity(
        path: String,
        expression: String,
    ): PomskyConfigIssue? {
        val ir = Expr
            .parse(expression)
            .first
            ?.compileToIr(expression, OPTIONS)
            ?.first ?: return null
        val report = ComplexityScorer.score(ir)
        if (report.level != ComplexityReport.Level.HIGH) return null
        val factors = report.factors.map { it.description }.distinct()
        return PomskyConfigIssue(
            path = path,
            message = "$path: pomsky pattern is complex (score ${report.score}): ${factors.joinToString("; ")}",
            hint = "Simplify the pattern: avoid nesting unbounded repetitions such as `(.* .*)*`.",
        )
    }
}
