package io.karandash.models.finding

import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Where a checked value diverges from the required format, stored as JSON in [WellKnownMetadataKeys.MISMATCH].
 *
 * @property value The checked value (e.g. the MR title).
 * @property position Index in [value] where matching stopped; `0` means the value is wrong from its first character.
 * @property expected Short description of what was expected at [position], rendered as code (e.g. `": "`).
 * @property group Named group of the format the divergence is in, or `null` outside any named group.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Mismatch(
    val value: String,
    val position: Int,
    val expected: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val group: String? = null,
) {
    /** The part of [value] that matched before [position], never ending inside a surrogate pair. */
    val matchedPrefix: String
        get() {
            val end = position.coerceIn(0, value.length)
            return value.take(if (end > 0 && value[end - 1].isHighSurrogate()) end - 1 else end)
        }

    /** This mismatch as the [WellKnownMetadataKeys.MISMATCH] metadata value (`group` omitted when `null`). */
    fun toMetadataValue(): String = KarandashJson.ConfigEncoder.encodeToString(serializer(), this)

    /**
     * The "Where" text in [language] (`en`, `ru` or a language pack; others are `en`):
     * `` `<value>` - after `<matchedPrefix>` expected `<expected>` - found `<word>` (part `<group>`) ``, or "at the start expected …"
     * when [position] is `0`. [code] renders a code span; the default is a markdown code span.
     */
    fun describe(
        language: String? = null,
        code: (String) -> String = ::codeSpan,
    ): String {
        val where = if (matchedPrefix.isEmpty()) {
            FixHintText.AT_START.text(language)
        } else {
            "${FixHintText.AFTER.text(language)} ${code(matchedPrefix)}"
        }
        val found = foundAt(language, code)
        val part = group?.let { " (${FixHintText.PART.text(language)} ${code(it)})" }.orEmpty()
        return "${code(
            value
        )} — $where ${FixHintText.EXPECTED.text(language)} ${code(expected)} — ${FixHintText.FOUND.text(language)} $found$part"
    }

    /** What [value] has at [position]: its first word or character in code, "whitespace" for a blank, or "nothing" at the end. */
    private fun foundAt(
        language: String?,
        code: (String) -> String,
    ): String {
        val rest = value.drop(position.coerceAtLeast(0))
        val word = rest.takeWhile { it.isLetterOrDigit() || it == '_' }
        val token = word.ifEmpty { rest.take(1) }
        return when {
            rest.isEmpty() -> FixHintText.NOTHING.text(language)
            token.isBlank() -> FixHintText.WHITESPACE.text(language)
            else -> code(token)
        }
    }
}

/** Fixed words of a finding's fix hint in each supported output language; any other language reads as English. */
enum class FixHintText(
    override val en: String,
) : LocalizedText {
    HOW_TO_FIX("How to fix"),
    WHERE("Where"),
    FORMAT("Format"),
    AFTER("after"),
    AT_START("at the start"),
    EXPECTED("expected"),
    PART("part"),
    FOUND("found"),
    NOTHING("nothing"),
    WHITESPACE("whitespace"),
    ;

    /** This text in [language] (see [outputLanguage]). */
    fun text(language: String? = null): String = of().text(language)
}

/** The [WellKnownMetadataKeys.EXPECTED_FORMAT] of this finding, or `null` when absent or blank. */
fun Finding.expectedFormat(): String? = metadata[WellKnownMetadataKeys.EXPECTED_FORMAT]?.takeIf { it.isNotBlank() }

/** The [WellKnownMetadataKeys.MISMATCH] of this finding, or `null` when absent or not the documented JSON shape. */
fun Finding.mismatch(): Mismatch? {
    val raw = metadata[WellKnownMetadataKeys.MISMATCH] ?: return null
    return try {
        KarandashJson.Default.decodeFromString(Mismatch.serializer(), raw)
    } catch (_: IllegalArgumentException) {
        // malformed producer metadata renders as no "Where" line; SerializationException is an IllegalArgumentException
        null
    }
}

/**
 * The labelled fix-hint lines of this finding for plain-text reporters, in order: `Where: …` ([mismatch]),
 * `How to fix: …` ([Finding.suggestion]), `Format: …` ([expectedFormat]); absent parts are left out.
 */
fun Finding.fixHintLines(language: String? = null): List<String> =
    listOfNotNull(
        mismatch()?.let { "${FixHintText.WHERE.text(language)}: ${it.describe(language)}" },
        suggestion?.takeIf { it.isNotBlank() }?.let { "${FixHintText.HOW_TO_FIX.text(language)}: $it" },
        expectedFormat()?.let { "${FixHintText.FORMAT.text(language)}: ${codeSpan(it.lines().joinToString(" ") { l -> l.trim() })}" },
    )

/**
 * The metadata keys a reporter renders as the fix hint ([fixHintLines] plus the format explanation and diagram);
 * text reporters leave them out of their generic `key: value` metadata dump.
 */
val FIX_HINT_METADATA_KEYS: Set<String> = setOf(
    WellKnownMetadataKeys.EXPECTED_FORMAT,
    WellKnownMetadataKeys.MISMATCH,
    WellKnownMetadataKeys.FORMAT_EXPLAIN,
    WellKnownMetadataKeys.FORMAT_DIAGRAM,
)

/** A markdown code span around [text], with a double-backtick fence when [text] holds a backtick. */
fun codeSpan(text: String): String = if ('`' in text) "`` $text ``" else "`$text`"
