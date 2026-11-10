package io.karandash.validators

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.utils.finding.CommitFindingGroup
import io.karandash.utils.finding.FindingFactory
import io.karandash.utils.mr.shortSha

class LanguagePolicyValidator(
    private val config: LanguagePolicyConfig,
) {
    private val allowedRanges: List<IntRange> = buildAllowedRanges(config)

    fun validate(mr: MergeRequest): List<Finding> =
        buildList {
            if (config.unknownScripts.isNotEmpty()) {
                add(
                    FindingFactory.warn(
                        rule = LanguagePolicyRuleModule.RULE_ID,
                        message = "Unknown script name(s) in 'allowedScripts': ${config.unknownScripts.joinToString()}.",
                        suggestion = "Replace ${config.unknownScripts.joinToString()} in 'allowedScripts' with one of: " +
                            "${LanguagePolicyConfig.knownScriptNames().joinToString()}.",
                        explanation = "Unknown scripts are ignored and do not expand the allowed character set.",
                    ),
                )
            }

            val badRanges = config.additionalAllowedRanges.filter { parseUnicodeRange(it) == null }
            if (badRanges.isNotEmpty()) {
                add(
                    FindingFactory.warn(
                        rule = LanguagePolicyRuleModule.RULE_ID,
                        message = "Invalid Unicode range(s) in 'additionalAllowedRanges': ${badRanges.joinToString()}.",
                        suggestion = "Write each range as two hex code points, e.g. '0x0400-0x04FF'.",
                        explanation = "Invalid ranges are ignored and do not expand the allowed character set.",
                    ),
                )
            }

            if (config.checkMrTitle) {
                checkText(mr.title, "MR title")?.let(::add)
            }

            val description = mr.description
            if (config.checkMrDescription && !description.isNullOrBlank()) {
                checkText(stripNonProseContent(description), "MR description")?.let(::add)
            }

            if (config.checkBranchName) {
                checkText(mr.sourceBranch, "branch name")?.let(::add)
            }

            val subjects = mutableListOf<Pair<String, Finding>>()
            val bodies = mutableListOf<Pair<String, Finding>>()
            mr.commits.forEach { commit ->
                val lines = commit.message.lines()
                val body = if (lines.size > 1) lines.drop(1).joinToString("\n") else null
                val sha = commit.sha.shortSha()

                if (config.checkCommitTitle) {
                    checkText(lines.first(), "commit $sha subject")?.let { subjects.add(sha to it) }
                }

                if (config.checkCommitDescription && !body.isNullOrBlank()) {
                    checkText(stripNonProseContent(body), "commit $sha body")?.let { bodies.add(sha to it) }
                }
            }
            addAll(CommitFindingGroup.collapse(subjects, "have a subject not in ${config.languageName}"))
            addAll(CommitFindingGroup.collapse(bodies, "have a body not in ${config.languageName}"))
        }

    private fun stripNonProseContent(text: String): String =
        text
            .replace(FENCED_CODE_BLOCK_REGEX, "")
            .replace(INLINE_CODE_REGEX, "")
            .replace(MARKDOWN_IMAGE_REGEX, "")
            .replace(MARKDOWN_LINK_REGEX, "$1")
            .replace(BARE_URL_REGEX, "")
            .replace(HTML_COMMENT_REGEX, "")

    /** `Commit 7d40815 subject is not in English (Cyrillic found)`; the suggestion lists the letters to replace. */
    private fun checkText(
        text: String,
        entityName: String,
    ): Finding? {
        val disallowedChars = findDisallowedLetters(text)
        if (disallowedChars.isEmpty()) return null
        val shown = disallowedChars.take(MAX_SHOWN_CHARS)
        return FindingFactory.error(
            rule = LanguagePolicyRuleModule.RULE_ID,
            message = "${entityName.replaceFirstChar { it.uppercase() }} is not in ${config.languageName} " +
                "(${scriptsOf(disallowedChars)} found)",
            suggestion = "Rewrite the $entityName in ${config.languageName}, replacing " +
                "${shown.joinToString(", ") { "'$it'" }}.",
            explanation = "Language policy ensures consistent character encoding across the codebase.",
        )
    }

    /** The script names of [chars] (`Cyrillic`, `Cyrillic and Greek`), or `other letters` for a script not in [SCRIPT_RANGES]. */
    private fun scriptsOf(chars: List<Char>): String =
        chars
            .map { ch -> SCRIPT_RANGES.entries.firstOrNull { (_, ranges) -> ranges.any { ch.code in it } }?.key }
            .distinct()
            .map { it?.let(::scriptLabel) ?: "other letters" }
            .distinct()
            .joinToString(" and ")

    internal fun findDisallowedLetters(text: String): List<Char> =
        if (allowedRanges == LATIN_RANGES) {
            findNonLatinLetters(text)
        } else {
            findOutsideAllowed(text)
        }

    private fun findOutsideAllowed(text: String): List<Char> =
        text
            .filter { ch ->
                ch.isUnicodeLetter() && !isAllowedLetter(ch)
            }.toList()
            .distinct()

    private fun isAllowedLetter(ch: Char): Boolean = allowedRanges.any { ch.code in it }

    companion object {
        private const val MAX_SHOWN_CHARS = 5
        private val FENCED_CODE_BLOCK_REGEX = Regex("""```[\s\S]*?```""")
        private val INLINE_CODE_REGEX = Regex("""`[^`\n]+`""")
        private val MARKDOWN_IMAGE_REGEX = Regex("""!\[[^\]]*]\([^)]*\)""")
        private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]*?)]\([^)]*\)""")
        private val BARE_URL_REGEX = Regex("""https?://\S+""")
        private val HTML_COMMENT_REGEX = Regex("""<!--[\s\S]*?-->""")

        internal val SCRIPT_RANGES: Map<String, List<IntRange>> = mapOf(
            // Latin includes Latin Extended Additional (Vietnamese, ẞ); Cyrillic includes Cyrillic Supplement.
            "latin" to listOf(0x0041..0x024F, 0x1E00..0x1EFF),
            "cyrillic" to listOf(0x0400..0x04FF, 0x0500..0x052F),
            "greek" to listOf(0x0370..0x03FF),
            "arabic" to listOf(0x0600..0x06FF),
            "cjk" to listOf(0x4E00..0x9FFF),
            "hebrew" to listOf(0x0590..0x05FF),
            "devanagari" to listOf(0x0900..0x097F),
        )

        private val LATIN_RANGES = SCRIPT_RANGES.getValue("latin")

        private fun scriptLabel(key: String): String = if (key == "cjk") "CJK" else key.replaceFirstChar { it.uppercase() }

        /** Letters outside the Latin ranges: the whole check for a Latin-only policy. */
        internal fun findNonLatinLetters(text: String): List<Char> =
            text
                .filter { ch ->
                    ch.isUnicodeLetter() && LATIN_RANGES.none { ch.code in it }
                }.toList()
                .distinct()

        private fun buildAllowedRanges(config: LanguagePolicyConfig): List<IntRange> =
            buildList {
                for (script in config.allowedScripts) {
                    SCRIPT_RANGES[script.lowercase()]?.let { addAll(it) }
                }
                for (rangeStr in config.additionalAllowedRanges) {
                    parseUnicodeRange(rangeStr)?.let { add(it) }
                }
            }

        internal fun parseUnicodeRange(range: String): IntRange? {
            val parts = range.split("-")
            if (parts.size != 2) return null
            val start = parts[0].removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return null
            val end = parts[1].removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: return null
            return start..end
        }

        /**
         * Locale-independent Unicode letter check using [CharCategory].
         *
         * [Char.isLetter] on Kotlin/Native may delegate to the C library's
         * `iswctype()` which is locale-dependent. With `LANG=C` (common in CI
         * Docker images), it returns `false` for non-ASCII letters such as
         * Cyrillic. [CharCategory] is derived from the Unicode character database
         * compiled into the Kotlin stdlib, so it works regardless of OS locale.
         */
        private fun Char.isUnicodeLetter(): Boolean =
            when (category) {
                CharCategory.UPPERCASE_LETTER,
                CharCategory.LOWERCASE_LETTER,
                CharCategory.TITLECASE_LETTER,
                CharCategory.MODIFIER_LETTER,
                CharCategory.OTHER_LETTER -> true

                else -> false
            }
    }
}
