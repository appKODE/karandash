package io.karandash.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Report preferences (`report:` block).
 *
 * @property language Output language of the MR summary, inline comments and AI messages: a BCP-47 tag reduced to its
 *   base subtag (`ru-RU` reads as `ru`). Renderer strings are built in for `en` and `ru`; other languages come
 *   from a translation pack (`TranslationModule`, Polish ships built in); any other tag renders in `en`.
 * @property transparency How the summary shows how the review was made (`report.transparency`).
 * @property walkthrough Whether the AI MR summary carries a changes walkthrough (`report.walkthrough`).
 * @property style How much the human-facing formats show (`report.style`): `quiet`, `standard` (default, the compact
 *   layout) or `verbose` (every field, the layout before 2.x). Machine formats ignore it.
 */
@Serializable
data class ReportConfig(
    val language: String = DEFAULT_LANGUAGE,
    val transparency: ReportTransparencyConfig = ReportTransparencyConfig(),
    val walkthrough: WalkthroughMode = WalkthroughMode.OFF,
    val style: String = DEFAULT_STYLE,
) {
    companion object {
        const val DEFAULT_LANGUAGE = "en"

        /** `report.style` when unset. */
        const val DEFAULT_STYLE = "standard"

        private val NAMES = mapOf("english" to "en", "russian" to "ru", "polish" to "pl")

        /** Lowercased base subtag of [raw] (`ru-RU`, `ru_RU` -> `ru`); the English names of en, ru and pl map to their tag. */
        fun normalizeLanguage(raw: String): String {
            val base = raw
                .trim()
                .lowercase()
                .substringBefore('-')
                .substringBefore('_')
            return NAMES[base] ?: base.ifEmpty { DEFAULT_LANGUAGE }
        }
    }
}

/**
 * `report.transparency`: the provenance block of the markdown summary.
 *
 * @property summary Off (the summary as before), collapsed `<details>` (default) or expanded.
 * @property inlineWhy When true, each inline note ends with a one-line "why" footer.
 * @property flowDiagram When true, the block carries a Mermaid flow diagram of the run; the diagram is written to
 *   `.karandash/debug/review-flow.md` either way.
 */
@Serializable
data class ReportTransparencyConfig(
    val summary: TransparencyMode = TransparencyMode.COLLAPSED,
    val inlineWhy: Boolean = false,
    val flowDiagram: Boolean = false,
)

/** `report.transparency.summary` values. */
@Serializable
enum class TransparencyMode {
    @SerialName("off")
    OFF,

    @SerialName("collapsed")
    COLLAPSED,

    @SerialName("expanded")
    EXPANDED,
}

/** `report.walkthrough` values. */
@Serializable
enum class WalkthroughMode {
    @SerialName("off")
    OFF,

    @SerialName("collapsed")
    COLLAPSED,
}
