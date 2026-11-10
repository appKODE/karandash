package io.karandash.plugins.ai.core

import io.karandash.models.finding.Translations

/**
 * The fixed texts of the `@karandash why` and `@karandash ask` thread answers in English; other languages
 * (`report.language`, via `ai.responseLanguage`) come from a pack. `%1`, `%2`, ... are values.
 */
internal enum class ThreadAnswerText(
    val en: String,
) {
    WHY_TITLE("**Why this finding**"),
    ANALYZER_MODEL("- Analyzer: `%1`, model `%2`"),
    ANALYZER_NOT_AI("- Analyzer: `%1`, not an AI rule"),
    REASON("- Reason: %1"),
    INSTRUCTION("- Project instruction: `%1`"),
    LEARNING("- Learning: `%1` from `.karandash/learnings.yaml`"),
    CONFIDENCE("- Confidence: %1"),
    CONTEXT_SOURCES("- Context sources: %1"),
    FILTERS("- Filters: %1"),
    COVERAGE("- Coverage: %1"),
    FILTER_REMOVED("%1 (removed %2)"),
    FILTERS_REMOVED_BY("passed, others removed by: %1"),
    FILTERS_NONE("passed, none removed anything"),
    NOT_IN_RUN(
        "This finding is not in the current run (fixed, filtered or outside the reviewed changes), so there is " +
            "nothing to show for it.",
    ),
    ASK_DISABLED("`@karandash ask` is disabled. Set `ai.thread-commands.ask: true` to enable it."),
    ASK_NO_PROVIDER("`@karandash ask` cannot be answered: no AI provider is configured for this run."),
    ASK_EMPTY("Write the question after the command: `@karandash ask <question>`."),
    ASK_GAVE_UP("Could not answer this question, ask again with a new reply."),
    ASK_FAILED("Could not answer: the AI token budget is spent or the provider failed."),
    ;

    fun of(
        language: String?,
        vararg values: Any,
    ): String = values.foldIndexed(Translations.text(en, language)) { i, acc, v -> acc.replace("%${i + 1}", v.toString()) }
}
