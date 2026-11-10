package io.karandash.translations.polish

import io.karandash.Version
import io.karandash.api.PluginContext
import io.karandash.api.module.TranslationModule
import io.karandash.models.finding.PluralRule
import io.karandash.models.finding.TranslationPack
import io.karandash.models.finding.Translations
import kotlinx.serialization.json.JsonObject

/** The Polish language pack (`report.language: pl`): every built-in report and finding text in Polish. */
class PolishTranslationModule : TranslationModule {
    override val id: String = ID
    override val name: String = "Polish Translations"
    override val description: String = "Polish texts for reports and findings (report.language: pl)"
    override val version: String = Version.VERSION
    override val language: String = LANGUAGE
    override val pluralRule: PluralRule = PluralRule.WEST_SLAVIC

    /** The pack takes no settings: any config object is accepted, anything else is malformed. */
    override suspend fun initialize(context: PluginContext): Boolean = context.pluginConfig is JsonObject

    override fun translations(): Map<String, String> = POLISH_TEXTS

    companion object {
        const val ID: String = "io.karandash.translation.pl"
        const val LANGUAGE: String = "pl"

        /** Registers this pack in [Translations] without a plugin bootstrap (tests and embedders). */
        fun register() {
            val module = PolishTranslationModule()
            Translations.register(TranslationPack(module.language, module.pluralRule, module.translations()))
        }
    }
}
