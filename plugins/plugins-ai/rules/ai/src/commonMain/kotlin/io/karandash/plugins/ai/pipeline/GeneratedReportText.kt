package io.karandash.plugins.ai.pipeline

import io.karandash.models.finding.Translations

/**
 * The fixed texts of the reports the AI rule renders itself (check-run summary and annotations, the research, usefulness and routing reports) in English; other
 * languages (`report.language`, via `ai.responseLanguage`) come from a pack. `%1`, `%2`, ... are values.
 */
internal enum class GeneratedReportText(
    val en: String,
) {
    CHECK_RUN_TITLE("Karandash Validation"),
    CHECK_RUN_COUNTS("%1 errors, %2 warnings, %3 annotations"),
    CHECK_RUN_SUMMARY("Conclusion: %1, Annotations: %2"),
    PAGERANK_LINE("- %1: rank=%2"),
    CENTRALITY_LINE("- %1: centrality=%2"),
    COMPLEXITY_DELTA("Cognitive complexity increased by %1 across %2 file(s)"),
    REFACTORING_ONLY("Refactoring-only MR (%1 pattern(s))"),
    REFACTORING_MIXED("Mixed: %1 refactoring + %2 behavioral"),
    NOT_USEFUL("%1 of %2 finding(s) classified as not-useful (style/formatting)"),
    ROUTED("%1: %2 finding(s)"),
    ;

    fun of(
        language: String?,
        vararg values: Any,
    ): String = values.foldIndexed(Translations.text(en, language)) { i, acc, v -> acc.replace("%${i + 1}", v.toString()) }
}
