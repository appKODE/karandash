package io.karandash.patterns

import io.karandash.helpers.PatternPrefixes
import io.karandash.helpers.RegexHelper
import io.karandash.models.finding.Mismatch
import io.karandash.models.finding.codeSpan
import io.karandash.models.support.WellKnownMetadataKeys
import kotlinx.serialization.Serializable
import ru.kode.pomskykt.Expr
import ru.kode.pomskykt.analysis.MatchDiagnoser
import ru.kode.pomskykt.analysis.MatchDiagnosis
import ru.kode.pomskykt.analysis.RailroadDiagram
import ru.kode.pomskykt.analysis.TestGenerator
import ru.kode.pomskykt.analysis.TestGeneratorOptions
import ru.kode.pomskykt.decompiler.Decompiler
import ru.kode.pomskykt.decompiler.ExplainLocale
import ru.kode.pomskykt.format.PomskyFormatter
import ru.kode.pomskykt.options.CompileOptions
import ru.kode.pomskykt.options.RegexFlavor
import ru.kode.pomskykt.syntax.exprs.TestCase
import ru.kode.pomskykt.regex.Regex as PomskyIr

/**
 * How to fix a value that does not match a format pattern: the hint text a rule puts in a finding's
 * `suggestion`, plus the parts reporters render as the fix hint (see [metadata]).
 *
 * @property suggestion One imperative sentence: change the value to [value], or to match the format.
 * @property value The corrected value, or an example of the format when it is not [deterministic]; `null` when
 *   no example could be produced.
 * @property deterministic `true` when [value] is the checked text with only the format's missing literal
 *   tokens inserted, each the only literal the format allows there, so it is safe to apply automatically;
 *   `false` for a generated example or a correction that picked one of several allowed literals.
 * @property mismatch Where the checked text diverges from the format, or `null` when it cannot be told.
 * @property format The format in readable pomsky (formatted source, or the decompiled regex).
 * @property explanation The format in words, in the requested language.
 * @property diagram The format as a text railroad diagram.
 * @property examples The values the pattern's author wrote as `test { match ... }` cases, the first few; empty otherwise.
 */
@Serializable
data class PatternFix(
    val suggestion: String,
    val value: String? = null,
    val deterministic: Boolean = false,
    val mismatch: Mismatch? = null,
    val format: String? = null,
    val explanation: String? = null,
    val diagram: String? = null,
    val examples: List<String> = emptyList(),
) {
    /** The fix-hint metadata of a finding: format, mismatch, explanation and diagram; absent parts are left out. */
    fun metadata(): Map<String, String> =
        buildMap {
            format?.let { put(WellKnownMetadataKeys.EXPECTED_FORMAT, it) }
            mismatch?.let { put(WellKnownMetadataKeys.MISMATCH, it.toMetadataValue()) }
            explanation?.let { put(WellKnownMetadataKeys.FORMAT_EXPLAIN, it) }
            diagram?.let { put(WellKnownMetadataKeys.FORMAT_DIAGRAM, it) }
            if (examples.isNotEmpty()) put(WellKnownMetadataKeys.PATTERN_EXAMPLES, examples.joinToString("\n"))
        }

    companion object {
        private const val MAX_INSERTIONS = 5
        private const val MAX_EXAMPLES = 5
        private const val ONE_OF = "one of "
        private const val MAX_LISTED = 12

        private val LIBRARY_HINTS = listOf(
            "jira_ticket" to "Use a ticket key such as `PROJ-123`.",
            "conventional_commit" to "Use `type(scope): subject`, such as `feat: add login`.",
            "semver" to "Use a version such as `1.2.3`.",
        )

        /**
         * Builds the fix for [actual] that does not match [pattern] (`pomsky:`, `regex:`, `@named`, `glob:` or a
         * bare regex). [subject] names the value in the suggestion ("title", "commit message", "branch name").
         * [language] picks the explanation language: `ru*` is Russian, anything else (`pl` included) is English,
         * since pomsky-kt explains only in English and Russian. With [diagnose] `false` (formats searched
         * for inside a larger text) no mismatch is reported and no correction is tried, only an example is given.
         */
        fun of(
            pattern: String,
            actual: String,
            subject: String,
            language: String? = null,
            diagnose: Boolean = true,
        ): PatternFix {
            val source = FormatSource.of(pattern) ?: return fallback(subject, pattern, null)
            val verify = RegexHelper.compileOrNull(source.regex)
            val ir = source.ir
            if (ir == null || verify == null) return fallback(subject, source.format, source.hint)
            val diagnosis = if (diagnose) MatchDiagnoser.diagnose(ir, actual) else null
            val repaired = diagnosis?.let { repair(actual, it, ir, verify, MAX_INSERTIONS, exact = true) }
            val deterministic = repaired?.exact == true
            val value = repaired?.value ?: example(source, ir, verify, actual)
            val suggestion = when {
                deterministic -> "Change the $subject to ${codeSpan(checkNotNull(value))}."
                value != null -> withHint("Change the $subject to match the format, e.g. ${codeSpan(value)}.", source.hint)
                else -> withHint("Change the $subject to match the format.", source.hint)
            }
            return PatternFix(
                suggestion = suggestion,
                value = value,
                deterministic = deterministic,
                mismatch = diagnosis?.let { Mismatch(actual, it.position, readableExpected(it.expected, ir), it.missingGroup) },
                format = source.format,
                explanation = explain(source.regex, language),
                diagram = diagram(ir),
                examples = source.authorExamples.distinct().take(MAX_EXAMPLES),
            )
        }

        private fun fallback(
            subject: String,
            format: String,
            hint: String?,
        ): PatternFix = PatternFix(suggestion = withHint("Change the $subject to match the format.", hint), format = format)

        private fun withHint(
            text: String,
            hint: String?,
        ): String = if (hint == null) text else "$text $hint"

        /** A repaired value; [exact] is `false` when some insertion picked one literal among several alternatives. */
        private class Repair(
            val value: String,
            val exact: Boolean,
        )

        /**
         * Inserts the literal the format expects at the mismatch, repeatedly, until [value] matches. Returns `null`
         * when an expected token is not a literal or an insertion does not get the match further. Where the format
         * allows several literals the first that gets further is taken, and the result is not [Repair.exact].
         */
        private tailrec fun repair(
            value: String,
            diagnosis: MatchDiagnosis,
            ir: PomskyIr,
            verify: Regex,
            budget: Int,
            exact: Boolean,
        ): Repair? {
            if (budget == 0) return null
            val position = diagnosis.position
            val literals = expectedLiterals(diagnosis.expected)
            val stillExact = exact && literals.size == 1
            val next = insertions(value, position, literals).firstOrNull { candidate ->
                verify.matches(candidate) || (MatchDiagnoser.diagnose(ir, candidate)?.position ?: -1) > position
            } ?: return null
            if (verify.matches(next)) return Repair(next, stillExact)
            val nextDiagnosis = MatchDiagnoser.diagnose(ir, next) ?: return null
            return repair(next, nextDiagnosis, ir, verify, budget - 1, stillExact)
        }

        /** Each literal inserted at [position], first without the part [value] already has there (`": "` before `" x"`). */
        private fun insertions(
            value: String,
            position: Int,
            literals: List<String>,
        ): List<String> {
            val head = value.take(position)
            val rest = value.drop(position)
            return literals
                .flatMap { literal ->
                    val overlap = (literal.length - 1 downTo 1).firstOrNull { rest.startsWith(literal.takeLast(it)) } ?: 0
                    listOf(head + literal.dropLast(overlap) + rest, head + literal + rest)
                }.distinct()
        }

        /**
         * The diagnoser's `one of a, a, b, ...` as `one of: a, b, c` (the full list of [ir]'s literal alternatives when the
         * diagnoser cut it off, repeats removed, at most [MAX_LISTED] with `and K more` after them).
         */
        private fun readableExpected(
            expected: String,
            ir: PomskyIr,
        ): String {
            val list = expected.removePrefix(ONE_OF)
            if (list == expected) return expected
            val shown = list.split(", ").filter { it != "..." }.distinct()
            val all = (if (list.endsWith("...")) alternativesStartingWith(ir, shown) else null) ?: shown
            val more = all.size - MAX_LISTED
            return "one of: " + all.take(MAX_LISTED).joinToString(", ") + if (more > 0) " and $more more" else ""
        }

        /** The words of the first alternation in [ir] made only of literals that begins with [head], or `null`. */
        private fun alternativesStartingWith(
            ir: PomskyIr,
            head: List<String>,
        ): List<String>? =
            when (ir) {
                is PomskyIr.Alt -> {
                    val words = ir.alternation.alternatives.map { (it as? PomskyIr.Literal)?.content }
                    if (words.all { it != null } && words.take(head.size) == head) {
                        words.filterNotNull().distinct()
                    } else {
                        ir.alternation.alternatives.firstNotNullOfOrNull { alternativesStartingWith(it, head) }
                    }
                }

                is PomskyIr.Sequence -> {
                    ir.parts.firstNotNullOfOrNull { alternativesStartingWith(it, head) }
                }

                is PomskyIr.Group -> {
                    ir.group.parts.firstNotNullOfOrNull { alternativesStartingWith(it, head) }
                }

                is PomskyIr.Rep -> {
                    alternativesStartingWith(ir.repetition.inner, head)
                }

                else -> {
                    null
                }
            }

        /** The quoted literals among the `" or "`-joined expectations of a diagnosis (`"\"]\""` is `]`). */
        private fun expectedLiterals(expected: String): List<String> =
            expected
                .split(" or ")
                .filter { it.length > 2 && it.first() == '"' && it.last() == '"' && it.count { c -> c == '"' } == 2 }
                .map { it.substring(1, it.length - 1) }

        /** An example of the format: the author's own `test { match }` case first, else a generated one. */
        private fun example(
            source: FormatSource,
            ir: PomskyIr,
            verify: Regex,
            actual: String,
        ): String? {
            source.authorExamples.firstOrNull()?.let { return it }
            val options = TestGeneratorOptions(
                maxExamples = MAX_EXAMPLES,
                sampleTexts = listOf(actual).filter { it.isNotBlank() },
            )
            return try {
                // optional parts (`(scope)?`, `!?`) are left out: the sample text would be stuffed into them
                listOf(withoutOptionals(ir), ir)
                    .firstNotNullOfOrNull { candidate ->
                        TestGenerator.generate(candidate, options).matching.firstOrNull { it.isNotEmpty() && verify.matches(it) }
                    }?.let { spaced(it, actual, verify) }
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }

        /** `feat:Title` reads as `feat: Title`: a space goes before the sample text when the pattern still matches. */
        private fun spaced(
            example: String,
            sample: String,
            verify: Regex,
        ): String {
            val text = sample.trim()
            val at = example.indexOf(text)
            if (text.isEmpty() || at <= 0 || example[at - 1].isWhitespace() || example[at - 1].isLetterOrDigit()) return example
            val spaced = example.substring(0, at) + " " + example.substring(at)
            return if (verify.matches(spaced)) spaced else example
        }

        /** [ir] with every `x?` replaced by nothing. */
        private fun withoutOptionals(ir: PomskyIr): PomskyIr =
            when (ir) {
                is PomskyIr.Rep -> {
                    val rep = ir.repetition
                    if (rep.lower == 0 && rep.upper == 1) {
                        PomskyIr.Sequence(emptyList())
                    } else {
                        PomskyIr.Rep(rep.copy(inner = withoutOptionals(rep.inner)))
                    }
                }

                is PomskyIr.Sequence -> {
                    PomskyIr.Sequence(ir.parts.map(::withoutOptionals))
                }

                is PomskyIr.Alt -> {
                    PomskyIr.Alt(ir.alternation.copy(alternatives = ir.alternation.alternatives.map(::withoutOptionals)))
                }

                is PomskyIr.Group -> {
                    PomskyIr.Group(ir.group.copy(parts = ir.group.parts.map(::withoutOptionals)))
                }

                else -> {
                    ir
                }
            }

        /** [regex] in words: Russian for a `ru*` [language], English otherwise (pomsky-kt has no other locale). */
        private fun explain(
            regex: String,
            language: String?,
        ): String? {
            val locale = if (language
                    .orEmpty()
                    .trim()
                    .lowercase()
                    .startsWith("ru")
            ) {
                ExplainLocale.RU
            } else {
                ExplainLocale.EN
            }
            return try {
                Decompiler.explain(regex, RegexFlavor.Java, locale).explanation
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }

        private fun diagram(ir: PomskyIr): String? =
            try {
                RailroadDiagram.render(ir).takeIf { it.isNotBlank() }
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }

        /** True when [source] names [word] as a whole identifier. */
        private fun mentions(
            source: String,
            word: String,
        ): Boolean =
            generateSequence(source.indexOf(word).takeIf { it >= 0 }) { i -> source.indexOf(word, i + 1).takeIf { it >= 0 } }
                .any { i -> !source.isWordAt(i - 1) && !source.isWordAt(i + word.length) }

        private fun String.isWordAt(index: Int): Boolean = getOrNull(index)?.let { it.isLetterOrDigit() || it == '_' } == true

        /** The short hint for a known pattern-library name mentioned in [source], if any. */
        internal fun libraryHint(source: String): String? = LIBRARY_HINTS.firstOrNull { (name, _) -> mentions(source, name) }?.second
    }
}

/** A pattern compiled for fixing: its regex, IR, readable form, author examples and library hint. */
private class FormatSource(
    val regex: String,
    val ir: PomskyIr?,
    val format: String,
    val authorExamples: List<String>,
    val hint: String?,
) {
    companion object {
        private const val PREFIX_POMSKY = PatternPrefixes.POMSKY
        private const val PREFIX_GLOB = PatternPrefixes.GLOB
        private const val PREFIX_REGEX = PatternPrefixes.REGEX
        private const val PREFIX_NAMED = "@"
        private val OPTIONS = CompileOptions(flavor = RegexFlavor.Java)

        /** `null` for a glob or a pattern that does not compile. */
        fun of(pattern: String): FormatSource? =
            when {
                pattern.startsWith(PREFIX_GLOB) -> {
                    null
                }

                pattern.startsWith(PREFIX_POMSKY) -> {
                    pomsky(pattern.removePrefix(PREFIX_POMSKY).trim())
                }

                pattern.startsWith(PREFIX_NAMED) -> {
                    RegexHelper.resolveToRegex(pattern)?.pattern?.let {
                        regex(it, PatternFix.libraryHint(pattern.removePrefix(PREFIX_NAMED)))
                    }
                }

                pattern.startsWith(PREFIX_REGEX) -> {
                    regex(pattern.removePrefix(PREFIX_REGEX), null)
                }

                else -> {
                    regex(pattern, null)
                }
            }

        private fun compileToIr(expression: String): PomskyIr? =
            Expr
                .parse(expression)
                .first
                ?.compileToIr(expression, OPTIONS)
                ?.first

        private fun pomsky(expression: String): FormatSource? {
            val (regex, _, tests) = Expr.parseAndCompile(expression, OPTIONS)
            if (regex == null) return null
            val examples = tests.flatMap { it.cases }.mapNotNull { case ->
                when (case) {
                    is TestCase.Match -> case.testCaseMatch.literal.content
                    is TestCase.MatchAll -> null
                    is TestCase.Reject -> null
                }
            }
            return FormatSource(
                regex = regex,
                ir = compileToIr(expression),
                format = PomskyFormatter.format(expression) ?: expression,
                authorExamples = examples,
                hint = PatternFix.libraryHint(expression),
            )
        }

        private fun regex(
            regex: String,
            hint: String?,
        ): FormatSource? {
            if (regex.isBlank()) return null
            val decompiled = Decompiler.decompile(regex, RegexFlavor.Java).pomsky
            // the decompiled pomsky keeps `feat|fix` as whole words, the parsed IR splits it into characters
            val ir = decompiled?.let { compileToIr(it) } ?: try {
                Decompiler.parse(regex, RegexFlavor.Java)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
            return FormatSource(
                regex = regex,
                ir = ir,
                format = decompiled?.let { PomskyFormatter.format(it) ?: it } ?: regex,
                authorExamples = emptyList(),
                hint = hint,
            )
        }
    }
}
