package io.karandash.plugins.ai.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity

/**
 * Actionability check for AI file findings: a message that hedges ("consider", "might", "возможно")
 * is kept only when it also names a concrete failure ("throws", "leaks", "падает") at a specific line.
 * An ERROR finding is never dropped.
 *
 * Word-based on purpose (no regex, so Cyrillic behaves the same on every target).
 * limit: fixed English/Russian word lists; move them to config if another language needs them.
 */
internal object AiActionabilityFilter {
    private val HEDGE_PHRASES = listOf(
        "might",
        "may",
        "could",
        "consider",
        "possibly",
        "perhaps",
        "potentially",
        "maybe",
        "would be better",
        "would be nice",
        "может",
        "возможно",
        "стоит",
        "рассмотрите",
        "рассмотреть",
        "вероятно",
        "имеет смысл",
        "лучше бы",
    )

    /** English failure words, whole tokens with their inflections, so `nullable` or `breakpoint` is no failure. */
    private val FAILURE_WORDS = setOf(
        "throw",
        "throws",
        "threw",
        "thrown",
        "throwing",
        "crash",
        "crashes",
        "crashed",
        "crashing",
        "null",
        "npe",
        "nullpointerexception",
        "leak",
        "leaks",
        "leaked",
        "leaking",
        "leakage",
        "inject",
        "injects",
        "injected",
        "injection",
        "injections",
        "race",
        "races",
        "racy",
        "deadlock",
        "deadlocks",
        "deadlocked",
        "overflow",
        "overflows",
        "overflowed",
        "exception",
        "exceptions",
        "fail",
        "fails",
        "failed",
        "failing",
        "failure",
        "failures",
        "break",
        "breaks",
        "breaking",
        "broke",
        "broken",
        "wrong",
        "incorrect",
        "incorrectly",
        "never",
        "lost",
        "loss",
        "lose",
        "loses",
        "losing",
        "corrupt",
        "corrupts",
        "corrupted",
        "corrupting",
        "corruption",
        "bypass",
        "bypasses",
        "bypassed",
        "expose",
        "exposes",
        "exposed",
        "exposing",
        "exposure",
        "unsafe",
        "hang",
        "hangs",
        "hanging",
        "hung",
        "infinite",
        "vulnerable",
        "vulnerability",
        "vulnerabilities",
        "overwrite",
        "overwrites",
        "overwrote",
        "overwritten",
        "overwriting",
        "stale",
        "timeout",
        "timeouts",
        "regress",
        "regresses",
        "regressed",
        "regression",
        "regressions",
        "inconsistent",
        "inconsistency",
        "inconsistencies",
    )

    /**
     * Words too generic to name a failure alone ("duplicate block", "missing values", "background thread"):
     * only blocking together with a thread counts.
     */
    private val BLOCK_WORDS = setOf("block", "blocks", "blocked", "blocking")
    private val THREAD_WORDS = setOf("thread", "threads")

    /** Phrases holding a failure word that name a technique, not a failure. */
    private val BENIGN_PHRASES = listOf("dependency injection")

    /** Russian failure stems, matched as token prefixes: Russian inflects too much for a word list. */
    private val FAILURE_STEMS_RU = listOf(
        "пада",
        "упад",
        "исключени",
        "утечк",
        "гонк",
        "лома",
        "слома",
        "теря",
        "потер",
        "неверн",
        "уязвим",
    )

    /** True when [finding] is a hedged AI file finding without concrete evidence. */
    fun isHedgedWithoutEvidence(finding: Finding): Boolean {
        val location = finding.location as? Location.FileRef ?: return false
        if (finding.severity == Severity.ERROR) return false
        val padded = tokenize(finding.message).joinToString(" ", prefix = " ", postfix = " ")
        if (HEDGE_PHRASES.none { padded.contains(" $it ") }) return false
        val tokens = BENIGN_PHRASES.fold(padded) { text, phrase -> text.replace(" $phrase ", " ") }.split(' ')
        val namesFailure = tokens.any { token -> token in FAILURE_WORDS || FAILURE_STEMS_RU.any { token.startsWith(it) } } ||
            (tokens.any { it in BLOCK_WORDS } && tokens.any { it in THREAD_WORDS })
        return location.line == null || !namesFailure
    }

    private fun tokenize(message: String): List<String> =
        message
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
}
