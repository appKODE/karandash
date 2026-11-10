package io.karandash.plugins.outputs

import io.karandash.models.finding.Translations

/** The fixed texts of the email output in English; other languages (`report.language`) come from a pack. */
internal enum class EmailText(
    val en: String,
) {
    DEFAULT_SUBJECT("Karandash Validation Report"),
    ;

    fun of(language: String?): String = Translations.text(en, language)
}
