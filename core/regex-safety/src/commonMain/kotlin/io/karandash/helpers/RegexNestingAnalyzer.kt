package io.karandash.helpers

import kotlin.math.max

/**
 * Finds unbounded repetition nested inside a repeated group (`(a+)+`, `(\w+\s?)+`, `(.*)+`, `((a)+)+`).
 *
 * An unbounded atom (`+`, `*`, `{n,}`) inside a group repeated the same way is nesting, unless the group also has
 * a mandatory literal that the atom cannot match: `(?:\[KEY-\d+\])+` is safe because `\d` never matches `]`, so
 * each repetition is anchored. When every mandatory literal is matchable by the atom (`(?:, .+)*`) the group is
 * [Result.ambiguous]: accepted, but callers warn. A group with a top-level `|` has no mandatory literal.
 */
internal object RegexNestingAnalyzer {
    /** [depth]: 0 none, 1 flat, 2 nested; [ambiguous]: a repeated group whose literals the inner atom can match. */
    class Result(
        val depth: Int,
        val ambiguous: Boolean,
    )

    private enum class Verdict { FLAT, AMBIGUOUS, NESTED }

    private class Frame {
        var hasAlternation = false
        var hasRepeatedSubgroup = false
        val literals = mutableSetOf<Char>()
        val repeatedAtoms = mutableListOf<String>()
    }

    private class Quantifier(
        val end: Int,
        val repeating: Boolean,
        val mandatory: Boolean,
    )

    fun analyze(pattern: String): Result {
        val stack = ArrayDeque<Frame>().apply { addLast(Frame()) }
        var depth = 0
        var ambiguous = false
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '(' -> {
                    stack.addLast(Frame())
                    i = skipGroupPrefix(pattern, i + 1)
                }

                ')' -> {
                    val q = readQuantifier(pattern, i + 1)
                    if (stack.size > 1) {
                        val frame = stack.removeLast()
                        if (q.repeating) {
                            val verdict = verdict(frame)
                            depth = max(depth, if (verdict == Verdict.NESTED) 2 else 1)
                            ambiguous = ambiguous || verdict == Verdict.AMBIGUOUS
                        }
                        val parent = stack.last()
                        parent.repeatedAtoms += frame.repeatedAtoms
                        parent.hasRepeatedSubgroup = parent.hasRepeatedSubgroup || q.repeating || frame.hasRepeatedSubgroup
                    }
                    i = q.end
                }

                '|' -> {
                    stack.last().hasAlternation = true
                    i++
                }

                '^', '$' -> {
                    i++
                }

                else -> {
                    val atomEnd = atomEnd(pattern, i)
                    val atom = pattern.substring(i, atomEnd)
                    val q = readQuantifier(pattern, atomEnd)
                    if (q.repeating) stack.last().repeatedAtoms += atom
                    if (q.mandatory && !q.repeating) literalOf(atom)?.let { stack.last().literals += it }
                    i = q.end
                }
            }
        }
        return Result(depth, ambiguous)
    }

    private fun verdict(frame: Frame): Verdict {
        if (frame.hasRepeatedSubgroup) return Verdict.NESTED
        if (frame.repeatedAtoms.isEmpty()) return Verdict.FLAT
        val literals = if (frame.hasAlternation) emptySet() else frame.literals
        if (literals.isEmpty()) return Verdict.NESTED
        val anchored = frame.repeatedAtoms.all { atom -> literals.any { !atomMatches(atom, it) } }
        return if (anchored) Verdict.FLAT else Verdict.AMBIGUOUS
    }

    /** Whether the single atom (a char, escape, `.` or class) can match [c]; unparseable atoms count as matching. */
    private fun atomMatches(
        atom: String,
        c: Char,
    ): Boolean = SafeRegexCompiler.Default.compileOrNull(atom)?.matches(c.toString()) ?: true

    private fun literalOf(atom: String): Char? =
        when {
            atom.length == 1 && atom[0] != '.' -> atom[0]
            atom.length == 2 && atom[0] == '\\' && !atom[1].isLetterOrDigit() -> atom[1]
            else -> null
        }

    private fun atomEnd(
        p: String,
        i: Int,
    ): Int =
        when (p[i]) {
            '\\' -> escapeEnd(p, i)
            '[' -> classEnd(p, i)
            else -> i + 1
        }

    private fun escapeEnd(
        p: String,
        i: Int,
    ): Int {
        if (i + 1 >= p.length) return p.length
        val braced = p[i + 1] in BRACED_ESCAPES && i + 2 < p.length && p[i + 2] == '{'
        if (!braced) return i + 2
        val close = p.indexOf('}', i + 2)
        return if (close < 0) p.length else close + 1
    }

    private fun classEnd(
        p: String,
        start: Int,
    ): Int {
        var j = start + 1
        if (j < p.length && p[j] == '^') j++
        if (j < p.length && p[j] == ']') j++
        var nested = 0
        while (j < p.length) {
            when (p[j]) {
                '\\' -> j++
                '[' -> nested++
                ']' -> if (nested == 0) return j + 1 else nested--
            }
            j++
        }
        return p.length
    }

    private fun skipGroupPrefix(
        p: String,
        i: Int,
    ): Int {
        if (i >= p.length || p[i] != '?') return i
        val next = p.getOrNull(i + 1)
        return when {
            next == '<' && p.getOrNull(i + 2) in LOOKBEHIND_MARKS -> i + 3
            next == '<' -> p.indexOf('>', i).let { if (it < 0) p.length else it + 1 }
            next in GROUP_MARKS -> i + 2
            else -> skipFlags(p, i + 1)
        }
    }

    private fun skipFlags(
        p: String,
        start: Int,
    ): Int {
        var j = start
        while (j < p.length && (p[j].isLetter() || p[j] == '-')) j++
        return if (j < p.length && p[j] == ':') j + 1 else j
    }

    private fun readQuantifier(
        p: String,
        i: Int,
    ): Quantifier {
        val base = when (p.getOrNull(i)) {
            '?' -> Quantifier(i + 1, repeating = false, mandatory = false)
            '*' -> Quantifier(i + 1, repeating = true, mandatory = false)
            '+' -> Quantifier(i + 1, repeating = true, mandatory = true)
            '{' -> readBraces(p, i)
            else -> null
        } ?: return Quantifier(i, repeating = false, mandatory = true)
        val lazyOrPossessive = p.getOrNull(base.end) == '?' || p.getOrNull(base.end) == '+'
        return if (lazyOrPossessive) Quantifier(base.end + 1, base.repeating, base.mandatory) else base
    }

    /** `{n}`, `{n,}` or `{n,m}`; `null` when the brace is a literal. Repeating when it allows more than one match. */
    private fun readBraces(
        p: String,
        i: Int,
    ): Quantifier? {
        val close = p.indexOf('}', i)
        if (close < 0) return null
        val body = p.substring(i + 1, close)
        val min = body.substringBefore(',').toIntOrNull() ?: return null
        val upper = body.substringAfter(',', missingDelimiterValue = "-")
        val maxReps =
            when {
                upper.isEmpty() -> Int.MAX_VALUE
                upper == "-" -> min
                else -> upper.toIntOrNull() ?: return null
            }
        return Quantifier(close + 1, repeating = maxReps > 1, mandatory = min >= 1)
    }

    private val BRACED_ESCAPES = setOf('p', 'P', 'x')
    private val LOOKBEHIND_MARKS = setOf('=', '!')
    private val GROUP_MARKS = setOf(':', '=', '!', '>')
}
