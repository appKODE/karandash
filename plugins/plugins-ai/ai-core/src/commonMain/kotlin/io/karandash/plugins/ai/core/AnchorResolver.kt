package io.karandash.plugins.ai.core

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location

/**
 * Places an AI finding's line anchor on added code. Pure: the caller supplies the added lines.
 *
 * 1. A quoted snippet (`existingCode`) or, failing that, the first backticked symbol in the message moves the
 *    anchor to the nearest added code line within [RADIUS] lines that contains it. A symbol must be at least
 *    [MIN_SYMBOL_LENGTH] characters and match as a whole word, so `id` or `use` do not pull the anchor away.
 * 2. Otherwise an anchor on an added code line stays; one on an added `}`, blank or comment-only line moves to
 *    the nearest added code line within [RADIUS].
 * 3. An anchor on a line the MR did not add is dropped (`null`).
 * 4. Anything left (a snippet that is not there, or no code line near the brace) becomes an unverified
 *    file-level finding flagged with [AiFindingMetadataKeys.LINE_DEMOTED], which the summary shows.
 *
 * On a move or demotion [AiFindingMetadataKeys.LLM_LINE] keeps the line the model reported.
 *
 * A committable suggestion ([AiFindingMetadataKeys.SUGGESTED_CODE]) is kept only on an exact range of added lines:
 * the consecutive added lines, within [RADIUS] of the anchor, whose text is the `existingCode` snippet line for line,
 * or `line..endLine` when there is no snippet. Otherwise the suggested code is removed and the finding kept, so a
 * suggestion never replaces a context or deleted line or the wrong lines; a [AiRuleIds.CODE_SUGGESTIONS] companion,
 * which says nothing without its code, is dropped instead, unless it is a `correctness` or `security` suggestion.
 */
object AnchorResolver {
    /** How far, in lines, a snippet match or a brace snap may move the anchor. */
    const val RADIUS: Int = 3

    // limit: prefix heuristic; `# ` also hides Markdown headings, add per-language rules if doc reviews suffer
    // `* ` and `*/` are block-comment lines; a bare `*` prefix would also hide a pointer dereference (`*p = x;`)
    private val COMMENT_PREFIXES = listOf("//", "/*", "* ", "*/", "<!--", "# ")
    private val BARE_COMMENT_MARKERS = setOf("#", "*")
    private const val MIN_SYMBOL_LENGTH = 3
    private const val STRUCTURAL_CHARS = "{}()[];,"
    private const val BACKTICK = '`'

    // `code-suggestions` own categories whose explanation stands alone: a model often puts its fix in `existingCode`,
    // and a hardcoded key or a bug must not vanish with the misquoted code; a review-comments companion's category
    // is upper case (`SECURITY`) and its message only "Code suggestion (...)", so it is still dropped
    private val KEPT_WITHOUT_CODE = setOf("correctness", "security")

    /**
     * @param finding a finding whose location is a [Location.FileRef] with a line; anything else is returned as is.
     * @param added new-file line number to text of every added line in the finding's file.
     */
    fun resolve(
        finding: Finding,
        added: Map<Int, String>,
    ): Finding? {
        val loc = finding.location as? Location.FileRef ?: return finding
        val line = loc.line ?: return finding
        val snippet = finding.metadata[AiFindingMetadataKeys.EXISTING_CODE]?.let(::firstCodeLine)
        val byNeedle = if (snippet != null) {
            nearest(line, added) { normalize(it).contains(snippet) }
        } else {
            symbolOf(finding.message)?.let { symbol -> nearest(line, added) { containsWord(normalize(it), symbol) } }
        }
        val text = added[line]
        val placed = when {
            byNeedle != null -> moveTo(finding, loc, byNeedle)
            text == null -> null
            snippet != null -> demote(finding, loc)
            isAnchorable(text) -> finding
            else -> nearest(line, added) { true }?.let { moveTo(finding, loc, it) } ?: demote(finding, loc)
        }
        return placed?.let { fitSuggestion(it, added) }
    }

    private fun fitSuggestion(
        finding: Finding,
        added: Map<Int, String>,
    ): Finding? {
        if (finding.metadata[AiFindingMetadataKeys.SUGGESTED_CODE].isNullOrBlank()) return finding
        val loc = finding.location as? Location.FileRef
        val line = loc?.line ?: return withoutSuggestion(finding)
        val snippet = finding.metadata[AiFindingMetadataKeys.EXISTING_CODE]
            ?.takeIf { it.isNotBlank() }
            ?.trimEnd('\n')
            ?.lines()
            ?.map(::normalize)
        if (snippet == null) return if ((line..(loc.endLine ?: line)).all { it in added }) finding else withoutSuggestion(finding)
        val start = (0..RADIUS)
            .asSequence()
            .flatMap { d -> sequenceOf(line - d, line + d) }
            .firstOrNull { s -> snippet.indices.all { i -> added[s + i]?.let(::normalize) == snippet[i] } }
            ?: return withoutSuggestion(finding)
        // a null end line is widened to the snippet by AiMetadataTransformer, which pairs suggestions by reported line
        val endLine = if (snippet.size > 1 && loc.endLine != null) start + snippet.size - 1 else null
        if (start == line && endLine == loc.endLine) return finding
        val keepsLlmLine = start == line || AiFindingMetadataKeys.LLM_LINE in finding.metadata
        val moved = if (keepsLlmLine) {
            finding
        } else {
            finding.copy(
                metadata =
                    finding.metadata + (AiFindingMetadataKeys.LLM_LINE to line.toString())
            )
        }
        return moved.copy(location = loc.copy(line = start, endLine = endLine))
    }

    private fun withoutSuggestion(finding: Finding): Finding? =
        if (finding.rule == AiRuleIds.CODE_SUGGESTIONS && finding.metadata[AiFindingMetadataKeys.CATEGORY] !in KEPT_WITHOUT_CODE) {
            null
        } else {
            finding.copy(metadata = finding.metadata - AiFindingMetadataKeys.SUGGESTED_CODE)
        }

    /** True for a line that holds code: not blank, not only brackets and separators, not only a comment. */
    fun isAnchorable(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() &&
            t !in BARE_COMMENT_MARKERS &&
            !t.all { it in STRUCTURAL_CHARS } &&
            COMMENT_PREFIXES.none { t.startsWith(it) }
    }

    /** Nearest added code line to [line] within [RADIUS] whose text passes [match]; earlier line wins a tie. */
    private fun nearest(
        line: Int,
        added: Map<Int, String>,
        match: (String) -> Boolean,
    ): Int? =
        (0..RADIUS)
            .asSequence()
            .flatMap { d -> sequenceOf(line - d, line + d) }
            .firstOrNull { n -> added[n]?.let { isAnchorable(it) && match(it) } == true }

    private fun moveTo(
        finding: Finding,
        loc: Location.FileRef,
        target: Int,
    ): Finding {
        val line = checkNotNull(loc.line) { "moveTo needs a line" }
        if (target == line) return finding
        val delta = target - line
        return finding.copy(
            location = loc.copy(line = target, endLine = loc.endLine?.let { maxOf(it + delta, target) }),
            metadata = finding.metadata + (AiFindingMetadataKeys.LLM_LINE to line.toString()),
        )
    }

    private fun demote(
        finding: Finding,
        loc: Location.FileRef,
    ): Finding =
        finding.copy(
            location = Location.FileRef(path = loc.path),
            metadata = finding.metadata +
                mapOf(AiFindingMetadataKeys.LINE_DEMOTED to "true", AiFindingMetadataKeys.LLM_LINE to loc.line.toString()),
        )

    private fun firstCodeLine(snippet: String): String? = snippet.lineSequence().map(::normalize).firstOrNull { it.isNotEmpty() }

    private fun symbolOf(message: String): String? {
        val start = message.indexOf(BACKTICK)
        val end = if (start < 0) -1 else message.indexOf(BACKTICK, start + 1)
        return if (end > start + 1) normalize(message.substring(start + 1, end)).takeIf { it.length >= MIN_SYMBOL_LENGTH } else null
    }

    /** True when [word] occurs in [text] with no identifier character glued to either of its identifier-character ends. */
    private fun containsWord(
        text: String,
        word: String,
    ): Boolean =
        generateSequence(text.indexOf(word)) { i -> text.indexOf(word, i + 1).takeIf { it >= 0 } }
            .takeWhile { it >= 0 }
            .any { i ->
                val before = text.getOrNull(i - 1)
                val after = text.getOrNull(i + word.length)
                !(isIdent(word.first()) && before != null && isIdent(before)) && !(isIdent(word.last()) && after != null && isIdent(after))
            }

    private fun isIdent(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun normalize(text: String): String = text.split(' ', '\t', '\r').filter { it.isNotEmpty() }.joinToString(" ")
}
