package io.karandash.translations.russian

import io.karandash.Version
import io.karandash.api.PluginContext
import io.karandash.api.module.TranslationModule
import io.karandash.models.finding.PluralRule
import io.karandash.models.finding.TranslationPack
import io.karandash.models.finding.Translations
import kotlinx.serialization.json.JsonObject

/** The Russian language pack (`report.language: ru`): every built-in report and finding text in Russian. */
class RussianTranslationModule : TranslationModule {
    override val id: String = ID
    override val name: String = "Russian Translations"
    override val description: String = "Russian texts for reports and findings (report.language: ru)"
    override val version: String = Version.VERSION
    override val language: String = LANGUAGE
    override val pluralRule: PluralRule = PluralRule.EAST_SLAVIC

    /** The pack takes no settings: any config object is accepted, anything else is malformed. */
    override suspend fun initialize(context: PluginContext): Boolean = context.pluginConfig is JsonObject

    override fun translations(): Map<String, String> = RUSSIAN_TEXTS

    companion object {
        const val ID: String = "io.karandash.translation.ru"
        const val LANGUAGE: String = "ru"

        /** Registers this pack in [Translations] without a plugin bootstrap (tests and embedders). */
        fun register() {
            val module = RussianTranslationModule()
            Translations.register(TranslationPack(module.language, module.pluralRule, module.translations()))
        }
    }
}
