package io.karandash.helpers.reporter

import io.karandash.models.finding.PluralRule
import io.karandash.models.finding.Translations
import io.karandash.models.finding.outputLanguage

/** A report language of [ViewText]: English (the fallback) or a language with a translation pack. */
internal class ViewLanguage private constructor(
    val tag: String,
    val rule: PluralRule,
) {
    companion object {
        val EN = ViewLanguage("en", PluralRule.ONE_OTHER)

        fun of(language: String?): ViewLanguage =
            when (val tag = outputLanguage(language)) {
                EN.tag -> EN
                else -> ViewLanguage(tag, Translations.pack(tag)?.pluralRule ?: PluralRule.ONE_OTHER)
            }

        /** English and every language with a registered pack. */
        fun all(): List<ViewLanguage> = listOf(EN) + Translations.all().map { of(it.language) }
    }
}

/**
 * Report view texts in each [ViewLanguage]. A counted text has three forms (one, few, many) separated by `|` and `%d`
 * for the number; `%s` is a substituted value.
 */
internal enum class ViewText(
    private val en: String,
) {
    FAILED("Failed"),
    PASSED("Passed"),
    ERRORS("%d error|%d errors|%d errors"),
    WARNINGS(
        "%d warning|%d warnings|%d warnings",
    ),
    INFOS("%d info|%d info|%d info"),
    NEW("%d new|%d new|%d new"),
    FIXED("%d fixed|%d fixed|%d fixed"),
    SINCE_LAST_RUN("since last run"),
    RISK("Risk %s"),
    SKIPPED(
        "%d rule skipped|%d rules skipped|%d rules skipped",
    ),
    AI_FAILED("%d AI rule failed|%d AI rules failed|%d AI rules failed"),
    PASSED_RULES("Passed: %s"),
    AI_COVERAGE("AI reviewed %s files"),
    RUN_OF_MR("run %s of this MR"),
    MR("MR"),
    WHERE("Where"),
    RULE("Rule"),
    FINDING("Finding"),
    FIX("How to fix"),
    WHY("Why"),
    DETAILS("Details"),
    MORE("more"),
    ADD_LABEL("add label '%s'"),
    REMOVE_LABEL("remove label '%s'"),
    MARK_DRAFT("mark as draft"),
    MARK_READY("mark as ready"),
    SET_TITLE("set title to \"%s\""),
    UPDATE_DESCRIPTION("update description"),
    ;

    fun text(language: ViewLanguage): String = Translations.text(en, language.tag)

    fun format(
        language: ViewLanguage,
        value: String,
    ): String = text(language).replace("%s", value)

    fun count(
        language: ViewLanguage,
        n: Int,
    ): String = language.rule.count(text(language), n)
}

/**
 * The verdict word of a status line in [language], upper case: `PASSED`/`FAILED` in English (the text scripts grep for),
 * a translation pack's `Passed`/`Failed` otherwise (`НЕ ПРОЙДЕНО` in Russian). Same word as `ReportView.verdict` upper-cased.
 */
fun verdictLabel(
    passed: Boolean,
    language: String?,
): String = (if (passed) ViewText.PASSED else ViewText.FAILED).text(ViewLanguage.of(language)).uppercase()

/** `1 rule skipped` / `3 rules skipped` in [language]. */
fun skippedRulesText(
    count: Int,
    language: String?,
): String = ViewText.SKIPPED.count(ViewLanguage.of(language), count)
