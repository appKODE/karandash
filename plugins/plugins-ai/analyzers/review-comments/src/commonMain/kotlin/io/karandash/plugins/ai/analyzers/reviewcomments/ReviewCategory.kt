package io.karandash.plugins.ai.analyzers.reviewcomments

/**
 * The fixed review-comments category set: BUG, SEC, PERF, RACE, LEAK, DATA, QUESTION.
 *
 * [normalize] maps the earlier names (`bug_risk`, `security`, `performance`) and a few obvious synonyms
 * onto the set. Anything else, the retired TEST/CONV labels included, becomes QUESTION rather than being
 * dropped: the finding may still be right, but a label outside the defect set is not a confirmed defect,
 * so it is reported at INFO and never protected from the cap.
 */
internal object ReviewCategory {
    const val QUESTION = "QUESTION"

    val ALL = listOf("BUG", "SEC", "PERF", "RACE", "LEAK", "DATA", QUESTION)

    const val FALLBACK = QUESTION

    private val ALIASES = mapOf(
        "bug_risk" to "BUG",
        "bug" to "BUG",
        "correctness" to "BUG",
        "logic" to "BUG",
        "security" to "SEC",
        "performance" to "PERF",
        "concurrency" to "RACE",
        "race_condition" to "RACE",
        "resource_leak" to "LEAK",
        "memory_leak" to "LEAK",
        "data_loss" to "DATA",
        "data_integrity" to "DATA",
    )

    /** Prompt line listing the set with one-phrase meanings; both prompts share it. */
    val PROMPT_LINE: String =
        "category: one of ${ALL.joinToString(", ", "[", "]")} — BUG: wrong result or crash, " +
            "SEC: security, PERF: performance, RACE: concurrency, LEAK: unreleased resource or memory, " +
            "DATA: data loss or corruption, QUESTION: a possible defect that depends on code you cannot see"

    fun normalize(raw: String): String {
        val key = raw.trim()
        val upper = key.uppercase()
        if (upper in ALL) return upper
        return ALIASES[key.lowercase().replace('-', '_').replace(' ', '_')] ?: FALLBACK
    }
}
