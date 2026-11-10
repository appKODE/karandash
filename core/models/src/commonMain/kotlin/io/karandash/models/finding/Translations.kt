package io.karandash.models.finding

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

/** Russian `one` excludes numbers ending in 11 (11, 111, ...). */
private const val EAST_SLAVIC_ONE_EXCEPTION = 11

/**
 * How a language picks the form of a counted text. A counted text has three forms, `one|few|many`, separated by `|`;
 * a language without a `few` form repeats another form there.
 */
enum class PluralRule {
    /** `one` for exactly 1, `many` otherwise (English and most Western European languages). */
    ONE_OTHER,

    /** Russian and Ukrainian: `one` ends in 1 but not 11, `few` ends in 2-4 but not 12-14, `many` otherwise. */
    EAST_SLAVIC,

    /** Polish, Czech and Slovak: `one` for exactly 1, `few` ends in 2-4 but not 12-14, `many` otherwise. */
    WEST_SLAVIC,
    ;

    /** The index of the form of [n] among `one|few|many`: 0, 1 or 2. */
    fun form(n: Int): Int {
        val mod10 = n % 10
        val mod100 = n % 100
        val few = mod10 in 2..4 && mod100 !in 12..14
        return when {
            this == EAST_SLAVIC && mod10 == 1 && mod100 != EAST_SLAVIC_ONE_EXCEPTION -> 0
            this != EAST_SLAVIC && n == 1 -> 0
            this != ONE_OTHER && few -> 1
            else -> 2
        }
    }

    /** The form of [n] in the `one|few|many` [counted] text (the last form when it has fewer), `%d` filled with [n]. */
    fun count(
        counted: String,
        n: Int,
    ): String {
        val forms = counted.split('|')
        return forms[form(n).coerceAtMost(forms.lastIndex)].replace("%d", n.toString())
    }
}

/**
 * The translations of one language: [translations] maps an English template (the key, gettext style) to its text in
 * [language]; [pluralRule] picks the form of a counted (`one|few|many`) text.
 */
class TranslationPack(
    val language: String,
    val pluralRule: PluralRule,
    val translations: Map<String, String>,
)

/**
 * The language packs of this process, keyed by base language tag. Filled once at startup from the registered
 * translation modules; `en` is built in and never comes from a pack.
 */
object Translations {
    // limit: process-global, one set of packs per process; move it into the run scope if two runs ever need different packs.
    private val packs = atomic(emptyMap<String, TranslationPack>())

    /** Adds [pack] (replacing an earlier pack of its language); a pack for English, the built-in language, is ignored. */
    fun register(pack: TranslationPack) {
        val language = baseLanguage(pack.language)
        if (language.isEmpty() || language in BUILT_IN) return
        packs.update { it + (language to pack) }
    }

    /** The pack of [language] (read by its base tag), or `null` for a built-in language or one without a pack. */
    fun pack(language: String?): TranslationPack? = packs.value[baseLanguage(language)]

    /** Every registered pack. */
    fun all(): Collection<TranslationPack> = packs.value.values

    /** The translation of the [english] template in [language], or `null` when no pack has one. */
    fun translate(
        english: String,
        language: String?,
    ): String? = pack(language)?.translations?.get(english)

    /** The [english] text in [language]: a pack's translation, else [english]. */
    fun text(
        english: String,
        language: String?,
    ): String = translate(english, language) ?: english

    /** Removes every pack (tests). */
    fun clear() {
        packs.value = emptyMap()
    }

    internal const val EN = "en"
    internal const val RU = "ru"
    private val BUILT_IN = setOf(EN)
}

/** The base tag of [language], lowercased: `ru-RU` and `ru_RU` are `ru`; `null` is empty. */
internal fun baseLanguage(language: String?): String =
    language
        .orEmpty()
        .trim()
        .lowercase()
        .substringBefore('-')
        .substringBefore('_')
