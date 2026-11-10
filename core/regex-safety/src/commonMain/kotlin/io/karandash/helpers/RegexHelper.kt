package io.karandash.helpers

import io.karandash.api.errors.KarandashError
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.utils.takeWhole
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/**
 * Finding-producing wrapper around [SafeRegexCompiler].
 *
 * When a [PatternAwareCompiler] is injected via [injectPatternCompiler],
 * non-bare patterns (`@named`, `glob:`, `pomsky:`, `regex:`) are delegated
 * to it. Bare regex strings are always compiled locally via [SafeRegexCompiler].
 *
 * Each run injects its compiler before validation and removes only its own on completion
 * ([clearPatternCompiler] with the compiler), so overlapping runs don't clear each other's.
 */
object RegexHelper {
    /** Maximum allowed pattern length (delegated to [SafeRegexCompiler]). */
    const val MAX_PATTERN_LENGTH = SafeRegexCompiler.MAX_PATTERN_LENGTH

    private const val PATTERN_PREVIEW_SHORT = 50
    private const val PATTERN_PREVIEW_LONG = 80

    /** Compilers of the runs in flight, oldest first; each run injects and removes its own. */
    private val active = atomic<List<PatternAwareCompiler>>(emptyList())

    /** The most recently injected compiler of a run still in flight, or `null`. */
    val patternCompiler: PatternAwareCompiler?
        get() = active.value.lastOrNull()

    /** Injects a run's pattern-aware compiler. Called once per run before validation. */
    fun injectPatternCompiler(compiler: PatternAwareCompiler) {
        active.update { list -> list.filterNot { it === compiler } + compiler }
    }

    /** Clears every injected pattern compiler. Used in tests. */
    fun clearPatternCompiler() {
        active.value = emptyList()
    }

    /**
     * Removes only [compiler] (a run's own, on completion), so a run that finishes never clears
     * the compiler of a run still in flight.
     */
    fun clearPatternCompiler(compiler: PatternAwareCompiler) {
        active.update { list -> list.filterNot { it === compiler } }
    }

    /**
     * Compiles with the newest in-flight compiler that resolves [pattern], so overlapping runs
     * with different user `patterns:` each resolve their own names. Only the newest compiler's
     * error findings are kept when none resolves it.
     *
     * Fallback for callers that don't pass their run's compiler: same-named user patterns defined
     * differently by overlapping runs resolve to the newest run's definition. Validators pass the
     * run's own compiler to [tryCompile] / [tryCompileAll] instead, which never mixes runs.
     */
    private fun compileWithActive(
        compilers: List<PatternAwareCompiler>,
        pattern: String,
        rule: String,
        findings: MutableList<Finding>,
    ): Regex? {
        val newestErrors = mutableListOf<Finding>()
        compilers.last().compile(pattern, rule, newestErrors)?.let { return it }
        for (compiler in compilers.asReversed().drop(1)) {
            compiler.compile(pattern, rule, mutableListOf())?.let { return it }
        }
        findings += newestErrors
        return null
    }

    /**
     * Attempts to compile [pattern] as a [Regex] with ReDoS safety guards.
     *
     * If a [PatternAwareCompiler] is injected and [pattern] uses pattern syntax
     * (`@named`, `glob:`, `pomsky:`, `regex:`), compilation is delegated to
     * the pattern compiler. Bare regex strings are compiled locally.
     *
     * Returns the compiled regex on success, or `null` with an ERROR [Finding]
     * appended to [findings] on failure.
     */
    fun tryCompile(
        pattern: String,
        rule: String,
        findings: MutableList<Finding>,
    ): Regex? = tryCompile(null, pattern, rule, findings)

    /**
     * [tryCompile] with the calling run's own [compiler]: pattern syntax resolves only against that
     * run's patterns, so overlapping runs that define the same `@name` differently never mix.
     * A `null` [compiler] falls back to the compilers of the runs in flight.
     */
    fun tryCompile(
        compiler: PatternAwareCompiler?,
        pattern: String,
        rule: String,
        findings: MutableList<Finding>,
    ): Regex? {
        if (!looksLikePatternSyntax(pattern)) return compileRawRegex(pattern, rule, findings)
        if (compiler != null) return compiler.compile(pattern, rule, findings)
        val compilers = active.value
        if (compilers.isNotEmpty()) return compileWithActive(compilers, pattern, rule, findings)
        return compileRawRegex(pattern, rule, findings)
    }

    /**
     * Attempts to compile [pattern] with ReDoS safety guards, returning `null` on failure.
     *
     * Unlike [tryCompile], this does not require a rule ID or findings list.
     * Does NOT delegate to the pattern compiler - always compiles as raw regex.
     */
    fun compileOrNull(pattern: String): Regex? = SafeRegexCompiler.Default.compileOrNull(pattern)

    /**
     * Resolves a pattern (including `@named`, `pomsky:`, `glob:`, `regex:`) to its
     * compiled [Regex], or `null` if the pattern compiler is unavailable or compilation fails.
     *
     * Unlike [tryCompile], this does not append findings on failure - it is intended
     * for description/explanation use cases where a silent fallback is acceptable.
     */
    fun resolveToRegex(pattern: String): Regex? {
        val compilers = active.value
        if (compilers.isEmpty()) return null
        return compileWithActive(compilers, pattern, "", mutableListOf())
    }

    /**
     * Compiles all [patterns], collecting any compilation errors as ERROR [Finding]s
     * in [findings]. Patterns that fail to compile are silently dropped.
     */
    fun tryCompileAll(
        patterns: List<String>,
        rule: String,
        findings: MutableList<Finding>,
    ): List<Regex> = tryCompileAll(null, patterns, rule, findings)

    /** [tryCompileAll] with the calling run's own [compiler] (see the [tryCompile] overload). */
    fun tryCompileAll(
        compiler: PatternAwareCompiler?,
        patterns: List<String>,
        rule: String,
        findings: MutableList<Finding>,
    ): List<Regex> = patterns.mapNotNull { tryCompile(compiler, it, rule, findings) }

    /**
     * Computes the maximum quantifier nesting depth in [pattern].
     * Delegated to [SafeRegexCompiler.quantifierNestingDepth].
     */
    internal fun quantifierNestingDepth(pattern: String): Int = SafeRegexCompiler.Default.quantifierNestingDepth(pattern)

    /**
     * Returns `true` if [pattern] uses pattern-system syntax (non-bare).
     *
     * Recognized prefixes:
     * - `@` - named pattern (e.g. `@jira-ticket`, `@kebab-case`)
     * - `glob:` - text glob (e.g. `glob:{feat,fix}:*`)
     * - `pomsky:` - Pomsky DSL expression
     * - `regex:` - explicit regex (strip prefix, compile as regex)
     */
    fun looksLikePatternSyntax(pattern: String): Boolean = PatternPrefixes.of(pattern) != null

    /**
     * Appends a WARNING when [pattern] repeats a group whose literals its inner `.+`-like atom can match
     * (accepted for one release, then rejected like `(a+)+`).
     */
    fun warnIfAmbiguous(
        pattern: String,
        rule: String,
        findings: MutableList<Finding>,
    ) {
        if (!SafeRegexCompiler.Default.hasAmbiguousRepetition(pattern)) return
        findings += Finding(
            severity = Severity.WARNING,
            message = "Regex pattern repeats a group whose literals its inner quantifier can also match, which " +
                "risks catastrophic backtracking and will be rejected in a later release; anchor each " +
                "repetition with a literal the quantifier cannot match: '${pattern.takeWhole(PATTERN_PREVIEW_LONG)}'",
            rule = rule,
        )
    }

    /** Compiles a bare regex string via [SafeRegexCompiler] with finding production. */
    private fun compileRawRegex(
        pattern: String,
        rule: String,
        findings: MutableList<Finding>,
    ): Regex? =
        when (val result = SafeRegexCompiler.Default.compile(pattern)) {
            is SafeRegexCompiler.CompileResult.Success -> {
                warnIfAmbiguous(pattern, rule, findings)
                result.regex
            }

            is SafeRegexCompiler.CompileResult.TooLong -> {
                findings += Finding(
                    severity = Severity.ERROR,
                    message = "Regex pattern too long " +
                        "(${result.length} > ${result.maxLength} chars): " +
                        "'${pattern.takeWhole(PATTERN_PREVIEW_SHORT)}...'",
                    rule = rule,
                    errorCode = KarandashError.INVALID_REGEX_PATTERN.code,
                )
                null
            }

            is SafeRegexCompiler.CompileResult.NestedQuantifiers -> {
                findings += Finding(
                    severity = Severity.ERROR,
                    message = "Regex pattern has nested quantifiers " +
                        "(depth ${result.depth}) which can cause catastrophic " +
                        "backtracking: '${pattern.takeWhole(PATTERN_PREVIEW_LONG)}'",
                    rule = rule,
                    errorCode = KarandashError.INVALID_REGEX_PATTERN.code,
                )
                null
            }

            is SafeRegexCompiler.CompileResult.InvalidPattern -> {
                findings += Finding(
                    severity = Severity.ERROR,
                    message = "Invalid regex pattern '$pattern': ${result.message}",
                    rule = rule,
                    errorCode = KarandashError.INVALID_REGEX_PATTERN.code,
                )
                null
            }
        }
}
