package io.karandash.models.finding

import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The output language of [language], a BCP-47 tag read by its base (`ru-RU` is `ru`): a language with a registered
 * [TranslationPack] (Russian and Polish ship as built-in packs), or `en` (built in) for any other.
 */
fun outputLanguage(language: String?): String {
    val base = baseLanguage(language)
    return if (Translations.pack(base) != null) base else Translations.EN
}

/**
 * A fixed text of a module in English, implemented by the module's text enum ([FixHintText] style). `{0}`, `{1}`, …
 * in a text stand for the arguments of [of] (user data: names, paths, counts). Every other language comes from a
 * [TranslationPack] keyed by the English text.
 */
interface LocalizedText {
    val en: String

    /** This text in every language with `{n}` replaced by the `n`-th of [args]; an inserted argument is never re-scanned. */
    fun of(vararg args: Any): LocalizedString {
        val values = args.map(Any::toString)
        val english = fill(en, values)
        val russian = Translations.translate(en, Translations.RU)?.let { fill(it, values) } ?: english
        return LocalizedString(en = english, ru = russian, pl = english, template = en, args = values)
    }
}

/**
 * One text in each output language, made by [LocalizedText.of]. [template] and [args] are the English template and its
 * arguments, which a [TranslationPack] language translates; a text without a template (built by hand or by [plus]) or
 * without a translation falls back to [ru] for Russian, to [pl] for Polish and to [en] for any other language. [ru] and
 * [pl] serve hand-built texts; [LocalizedText.of] stores the Russian pack's text (English without it) in [ru] and English in [pl].
 */
@Serializable
data class LocalizedString(
    val en: String,
    val ru: String = en,
    val pl: String = en,
    val template: String? = null,
    val args: List<String> = emptyList(),
) {
    /** This text in [language] (see [outputLanguage]). */
    fun text(language: String? = null): String {
        val base = baseLanguage(language)
        val translated = template?.let { Translations.translate(it, base) }
        return when {
            translated != null -> fill(translated, args)
            base == Translations.RU -> ru
            base == POLISH -> pl
            else -> en
        }
    }

    /** The two texts joined, language by language; the result has no template. */
    operator fun plus(other: LocalizedString): LocalizedString = LocalizedString(en + other.en, ru + other.ru, pl + other.pl)
}

/** The language whose text [LocalizedString.pl] still stores for hand-built texts. */
private const val POLISH = "pl"

private val PLACEHOLDER = Regex("""\{(\d+)\}""")

private fun fill(
    template: String,
    args: List<String>,
): String = PLACEHOLDER.replace(template) { match -> args.getOrNull(match.groupValues[1].toInt()) ?: match.value }

/**
 * The texts of a finding in one language; `null` parts are left as they are. The [TEMPLATE] entry holds the English
 * templates instead, with their arguments in the `*Args` parts.
 */
@Serializable
private data class FindingTexts(
    val message: String? = null,
    val explanation: String? = null,
    val suggestion: String? = null,
    val messageArgs: List<String>? = null,
    val explanationArgs: List<String>? = null,
    val suggestionArgs: List<String>? = null,
)

/** The [FindingTexts] entry of the English templates, which a [TranslationPack] language translates. */
private const val TEMPLATE = "template"

private val textsSerializer = MapSerializer(String.serializer(), FindingTexts.serializer())

private fun Finding.localizedTexts(): Map<String, FindingTexts> {
    val raw = metadata[WellKnownMetadataKeys.LOCALIZED_TEXT] ?: return emptyMap()
    return try {
        KarandashJson.Default.decodeFromString(textsSerializer, raw)
    } catch (_: IllegalArgumentException) {
        // malformed producer metadata leaves the finding in English; SerializationException is an IllegalArgumentException
        emptyMap()
    }
}

/**
 * This finding with the given texts: the English one becomes [Finding.message], [Finding.explanation] or
 * [Finding.suggestion], the others (and the English templates, for pack languages) go to
 * [WellKnownMetadataKeys.LOCALIZED_TEXT] for [inLanguage]. Parts not given keep what an earlier call stored.
 */
fun Finding.withText(
    message: LocalizedString? = null,
    explanation: LocalizedString? = null,
    suggestion: LocalizedString? = null,
): Finding {
    val stored = localizedTexts()

    fun texts(
        language: String,
        pick: (LocalizedString) -> String,
    ): FindingTexts {
        val old = stored[language] ?: FindingTexts()
        return FindingTexts(
            message = message?.let(pick) ?: old.message,
            explanation = explanation?.let(pick) ?: old.explanation,
            suggestion = suggestion?.let(pick) ?: old.suggestion,
        )
    }

    fun templates(): FindingTexts {
        val old = stored[TEMPLATE] ?: FindingTexts()
        return FindingTexts(
            message = if (message != null) message.template else old.message,
            explanation = if (explanation != null) explanation.template else old.explanation,
            suggestion = if (suggestion != null) suggestion.template else old.suggestion,
            messageArgs = if (message != null) message.args.takeIf { message.template != null } else old.messageArgs,
            explanationArgs = if (explanation != null) explanation.args.takeIf { explanation.template != null } else old.explanationArgs,
            suggestionArgs = if (suggestion != null) suggestion.args.takeIf { suggestion.template != null } else old.suggestionArgs,
        )
    }
    val all = mapOf(
        "en" to texts("en", LocalizedString::en),
        "ru" to texts("ru", LocalizedString::ru),
        POLISH to texts(POLISH, LocalizedString::pl),
        TEMPLATE to templates(),
    )
    return copy(
        message = message?.en ?: this.message,
        explanation = explanation?.en ?: this.explanation,
        suggestion = suggestion?.en ?: this.suggestion,
        metadata = metadata + (WellKnownMetadataKeys.LOCALIZED_TEXT to KarandashJson.ConfigEncoder.encodeToString(textsSerializer, all)),
    )
}

/**
 * This finding with each of its texts that is exactly the English of one of [texts] (a fixed text, no `{n}`) stored in
 * every language, as [withText] does; a finding with no such text is returned as it is.
 */
fun Finding.withFixedText(texts: Iterable<LocalizedText>): Finding {
    fun match(current: String?): LocalizedString? = current?.let { text -> texts.firstOrNull { it.en == text }?.of() }
    val message = match(message)
    val explanation = match(explanation)
    val suggestion = match(suggestion)
    return if (message == null && explanation == null && suggestion == null) this else withText(message, explanation, suggestion)
}

/**
 * This finding as a report in [language] shows it: each text stored by [withText] in that language, and without the
 * [WellKnownMetadataKeys.LOCALIZED_TEXT] key. A text changed after [withText] (no longer the stored English) is kept.
 */
fun Finding.inLanguage(language: String?): Finding {
    if (WellKnownMetadataKeys.LOCALIZED_TEXT !in metadata) return this
    val all = localizedTexts()
    val stripped = copy(metadata = metadata - WellKnownMetadataKeys.LOCALIZED_TEXT)
    val en = all["en"] ?: return stripped
    val target = all.translated(outputLanguage(language)) ?: return stripped

    fun swap(
        current: String?,
        english: String?,
        localized: String?,
    ): String? = if (localized != null && current == english) localized else current
    return stripped.copy(
        message = swap(message, en.message, target.message) ?: message,
        explanation = swap(explanation, en.explanation, target.explanation),
        suggestion = swap(suggestion, en.suggestion, target.suggestion),
    )
}

/** The texts of [language]: a built-in entry, or each stored template translated by its pack (else the stored text). */
private fun Map<String, FindingTexts>.translated(language: String): FindingTexts? {
    val stored = this[language]
    if (language == Translations.EN) return stored
    val templates = this[TEMPLATE] ?: return stored

    fun part(
        template: String?,
        args: List<String>?,
        fallback: String?,
    ): String? = template?.let { Translations.translate(it, language) }?.let { fill(it, args.orEmpty()) } ?: fallback
    return FindingTexts(
        message = part(templates.message, templates.messageArgs, stored?.message),
        explanation = part(templates.explanation, templates.explanationArgs, stored?.explanation),
        suggestion = part(templates.suggestion, templates.suggestionArgs, stored?.suggestion),
    )
}
