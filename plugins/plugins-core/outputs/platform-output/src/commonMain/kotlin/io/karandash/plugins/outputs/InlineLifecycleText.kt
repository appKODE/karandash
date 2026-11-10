package io.karandash.plugins.outputs

import io.karandash.models.finding.Translations

/**
 * The inline-lifecycle lines of the summary comment in English; other languages (`report.language`) from a pack.
 * `%1`..`%3` are counts; [UPDATED] labels the time a comment was last posted or edited. [FIXED_IN], [FIXED] and [LOOKS_FIXED]
 * take the head sha. The finding text of an inline note is never translated: the reconciler reads its severity words.
 */
internal enum class InlineLifecycleText(
    val en: String,
) {
    DELTA(
        "**Inline review:** %1 open, %2 fixed",
    ),
    OVERFLOW("%1 more not posted inline"),
    INLINE_ONLY("+%1 in inline comments"),
    UPDATED("updated"),
    FIXED_IN("✅ Fixed in %1"),
    FIXED("Fixed in %1."),
    LOOKS_FIXED("Looks fixed in %1."),
    LISTING_FAILED(
        "**Inline review:** Inline comments were skipped this run: review threads could not be listed.",
    ),
    ;

    /** This text in [language] (a BCP-47 tag, region ignored; English when unset or without a translation pack), counts filled in. */
    fun of(
        language: String?,
        vararg counts: Int,
    ): String {
        val text = Translations.text(en, language)
        return counts.foldIndexed(text) { i, acc, n -> acc.replace("%${i + 1}", n.toString()) }
    }

    /** This text in [language] with `%1` set to [sha]. */
    fun of(
        language: String?,
        sha: String,
    ): String = Translations.text(en, language).replace("%1", sha)
}
