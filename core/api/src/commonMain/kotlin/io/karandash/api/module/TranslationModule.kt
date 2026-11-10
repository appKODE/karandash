package io.karandash.api.module

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.models.finding.PluralRule

/**
 * A language pack: the report texts in one more output language (`report.language`). English and Russian are built
 * in; every other language comes from a translation module.
 *
 * The key of each translation is the English template (gettext style), exactly as the built-in text has it, with
 * its placeholders (`{0}`, `%s`, `%d`, `%1`) kept. A counted text has three forms, `one|few|many`, separated by `|`;
 * [pluralRule] picks the form. A template without a translation renders in English.
 *
 * The host reads the three members once at startup, so a guest module serves them as plain data.
 *
 * @see ModuleType.TRANSLATION
 */
public interface TranslationModule : Module {
    override val type: ModuleType get() = ModuleType.TRANSLATION

    /** The BCP-47 base tag of the language, such as `pl` or `de`; a region (`pl-PL`) is ignored. */
    public val language: String

    /** How the language picks the form of a counted text. */
    public val pluralRule: PluralRule

    /** English template to translated template. */
    public fun translations(): Map<String, String>
}
