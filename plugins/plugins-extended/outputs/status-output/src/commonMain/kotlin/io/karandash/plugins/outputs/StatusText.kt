package io.karandash.plugins.outputs

import io.karandash.models.finding.PluralRule
import io.karandash.models.finding.Translations

/**
 * The status texts in English; other languages (`report.language`) from a pack. `%1`, `%2`, `%3` are values; a counted
 * text has three forms `one|few|many` with `%d` for the number (English keeps `error(s)`).
 */
internal enum class StatusText(
    val en: String,
) {
    PASSED("Passed"),
    FAILED("Failed"),
    ERRORS("%d error(s)|%d error(s)|%d error(s)"),
    WARNINGS("%d warning(s)|%d warning(s)|%d warning(s)"),
    ISSUES("%d issue(s)|%d issue(s)|%d issue(s)"),
    SUMMARY("karandash found %1: %2, %3."),
    ;

    /** This text in [language] (English when unset or without a pack), `%1`.. filled with [values]. */
    fun of(
        language: String?,
        vararg values: Any,
    ): String = values.foldIndexed(Translations.text(en, language)) { i, acc, v -> acc.replace("%${i + 1}", v.toString()) }

    /** The form of [n] of this counted text in [language]. */
    fun count(
        language: String?,
        n: Int,
    ): String = (Translations.pack(language)?.pluralRule ?: PluralRule.ONE_OTHER).count(Translations.text(en, language), n)
}
