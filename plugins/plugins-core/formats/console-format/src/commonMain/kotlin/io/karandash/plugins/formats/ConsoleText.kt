package io.karandash.plugins.formats

import io.karandash.models.finding.PluralRule
import io.karandash.models.finding.Translations

/**
 * The fixed console texts in English; other languages (`report.language`) from a pack. `%1`, `%2` are values; a
 * counted text has three forms `one|few|many` with `%d` for the number (English keeps `issue(s)`). `Validation %1`
 * keeps `Validation PASSED`/`FAILED` in English, the line scripts grep for.
 */
internal enum class ConsoleText(
    val en: String,
) {
    AI_REVIEW("AI review"),
    ERRORS_TITLE("Errors"),
    WARNINGS_TITLE("Warnings"),
    INFO_TITLE("Info"),
    NO_ISSUES("No issues found"),
    FOUND_ISSUES("Found %d issue(s):|Found %d issue(s):|Found %d issue(s):"),
    VALIDATION("Validation %1"),
    CATEGORIES("Categories: %1"),
    CHANGES_SUMMARY("Changes summary:"),
    POLICIES("Policies (%1 evaluated, %2 matched):"),
    SKIPPED_RULES("Skipped rules (%1):"),
    RULE_LABEL("Rule"),
    FORMAT_DIAGRAM("Format diagram:"),
    LOCATION("Location"),
    EXPLANATION("Explanation"),
    ATTACHMENT("Attachment"),
    ERROR_LABEL("Error"),
    SUGGESTED_CHANGE("Suggested change (lines %1-%2):"),
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
