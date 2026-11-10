package io.karandash.plugins.ai.rules

import io.karandash.models.finding.Translations

/**
 * The fixed texts of the pre-merge checklist in English; other output languages (`report.language`) come from a
 * translation pack, as for the markdown summary. `%d` is a count, `%m` the row cap. Check names, ticket titles and
 * model reasons are never translated.
 */
internal enum class PreMergeChecksText(
    val en: String,
) {
    SECTION_TITLE("Pre-merge checklist"),
    TICKET_ROW("Ticket compliance"),
    NO_PATH_MATCH(
        "no changed file matches its paths",
    ),
    TICKET_SUMMARY(
        "%d of %m ticket requirements met",
    ),
    NO_TICKET("no linked ticket"),
    MORE_TICKETS(
        "%d more ticket rows not checked (limit %m)",
    ),
    NO_SERVICE("not evaluated: no AI service"),
    CALL_FAILED(
        "not evaluated: the AI call failed",
    ),
    NO_VERDICT(
        "not evaluated: no verdict in the reply",
    ),
    ;

    /** This text in [language] (a BCP-47 tag; `ru-RU` reads as `ru`); a translation pack's text for a pack language, else English. */
    fun of(language: String?): String = Translations.text(en, language)
}
