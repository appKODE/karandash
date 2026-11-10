package io.karandash.utils.finding

import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.utils.mr.addedLinesWithNumbers

/**
 * Fingerprint of a finding's anchor, stable across runs: rule, path, the text of the anchored line (whitespace
 * collapsed) and which occurrence of that text it is. No message text and no line number, so a rewording LLM or
 * code inserted above the anchor keeps the same value. Used to match a finding to the inline thread posted for it
 * earlier.
 *
 * Paths are canonicalized (backslashes to `/`, a leading `./` removed); the head (new) path of a change is the canonical one. A finding
 * on a renamed file's old path is not stamped: [FileChange] carries no old path.
 *
 * Known limitations:
 * - The occurrence index counts identical lines above the anchor, so inserting a line with the same text above
 *   an existing one shifts the later line's occurrence and changes its fingerprint.
 * - Blank and bracket-only lines (`}`, `);`) get no fingerprint, so a finding anchored there carries none and
 *   thread matching falls back to `fp` or a message prefix.
 *
 * Separate from [FindingFingerprint], whose value is persisted and must not change.
 */
object AnchorFingerprint {
    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val STRUCTURAL_CHARS = "{}()[];,"

    /**
     * Returns a 16-character hex fingerprint of [rule], the canonical [path], the normalized [anchorLineText] and
     * [occurrence], the 0-based index of that normalized text among the file's identical lines (so the second
     * `return null` differs from the first). Occurrence 0 hashes as rule, path and text alone.
     */
    fun generate(
        rule: String,
        path: String,
        anchorLineText: String,
        occurrence: Int = 0,
    ): String {
        val suffix = if (occurrence == 0) "" else "#$occurrence"
        val lineHash = FindingFingerprint.stableHash(normalize(anchorLineText))
        return FindingFingerprint.stableHash("$rule|${canonicalPath(path)}|$lineHash$suffix")
    }

    /** True for text worth anchoring to: not blank and not only brackets and separators (`}`, `);`). */
    private fun isAnchorable(text: String): Boolean = normalize(text).let { t -> t.isNotEmpty() && !t.all { it in STRUCTURAL_CHARS } }

    /** [path] with backslashes turned into `/` and a leading `./` removed. */
    private fun canonicalPath(path: String): String = path.replace('\\', '/').removePrefix("./")

    /**
     * Each fingerprint [generate] gives for [rule] on an anchorable added line of [change], with that line's number.
     * Context lines are not included, so a caller matching prior findings here does not find one anchored to a line
     * that is now unchanged context.
     */
    fun anchoredLines(
        rule: String,
        change: FileChange,
    ): Map<String, Int> =
        AnchorLines(change).added.entries.associate { (line, anchor) ->
            generate(rule, change.path, anchor.first, anchor.second) to line
        }

    /**
     * Every fingerprint [generate] could give for [rule] on an anchorable line of [content] (a file at head): each
     * line text at each occurrence index up to its count in the file. A superset, since an added line counts only
     * identical added lines; a fingerprint missing from it has no anchor line left in the file.
     */
    fun fingerprintsIn(
        rule: String,
        path: String,
        content: String,
    ): Set<String> =
        content
            .lines()
            .filter { isAnchorable(it) }
            .groupingBy { normalize(it) }
            .eachCount()
            .flatMapTo(mutableSetOf()) { (text, count) -> (0 until count).map { generate(rule, path, text, it) } }

    /**
     * Stores [generate] under [WellKnownMetadataKeys.ANCHOR_FINGERPRINT] on every finding anchored to an anchorable
     * line whose text is known from [changes] (an added line, or the file's new content). Findings that already
     * carry the key, or whose line text is unknown or not anchorable, are returned unchanged.
     */
    fun stamp(
        findings: List<Finding>,
        changes: List<FileChange>,
    ): List<Finding> {
        val byPath by lazy { changes.associateBy { canonicalPath(it.path) } }
        val linesByPath = mutableMapOf<String, AnchorLines>()
        return findings.map { finding ->
            val ref = finding.location as? Location.FileRef
            val line = ref?.line
            if (line == null || WellKnownMetadataKeys.ANCHOR_FINGERPRINT in finding.metadata) return@map finding
            val change = byPath[canonicalPath(ref.path)] ?: return@map finding
            val (text, occurrence) = linesByPath.getOrPut(change.path) { AnchorLines(change) }.at(line) ?: return@map finding
            finding.copy(
                metadata = finding.metadata +
                    (WellKnownMetadataKeys.ANCHOR_FINGERPRINT to generate(finding.rule, change.path, text, occurrence)),
            )
        }
    }

    private fun normalize(text: String): String = text.replace(WHITESPACE_REGEX, " ").trim()

    /** Normalized anchorable lines of one change with their occurrence index, built once per path. */
    private class AnchorLines(
        private val change: FileChange,
    ) {
        /** Added lines: occurrence counts identical added lines above, so it does not depend on [FileChange.newContent]. */
        val added: Map<Int, Pair<String, Int>> = indexed(change.addedLinesWithNumbers().map { it.lineNumber to it.content })

        private val content: Map<Int, Pair<String, Int>> by lazy {
            indexed(
                change.newContent
                    ?.lines()
                    .orEmpty()
                    .mapIndexed { i, text -> i + 1 to text }
            )
        }

        fun at(line: Int): Pair<String, Int>? = added[line] ?: content[line]

        private fun indexed(lines: List<Pair<Int, String>>): Map<Int, Pair<String, Int>> {
            val seen = mutableMapOf<String, Int>()
            return lines
                .filter { (_, text) -> isAnchorable(text) }
                .associate { (number, text) ->
                    val normalized = normalize(text)
                    val occurrence = seen[normalized] ?: 0
                    seen[normalized] = occurrence + 1
                    number to (normalized to occurrence)
                }
        }
    }
}
