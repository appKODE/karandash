package io.karandash.plugins.ai.core.context

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.SensitiveFileFilter
import io.karandash.plugins.ai.core.rules.AiContextQualityConfig
import io.karandash.plugins.ai.core.rules.DiffAnnotationsConfig
import io.karandash.utils.mr.HunkParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Deterministic notes on the unified diff, no model call. Flags a removed guard (a deleted condition, assertion,
 * early exit or feature/permission check that is not re-added anywhere in the file) and a moved statement (the same
 * normalized line deleted in one place and added in another, marked when a condition or block boundary lies between).
 * The notes are questions for the reviewer, so a heuristic miss costs a line of prompt, not a finding.
 *
 * On by default (`ai.contextQuality.diffAnnotations.enabled`): the block is empty unless a pattern hits and is capped at
 * [DiffAnnotations.MAX_NOTES_PER_FILE] per file and [DiffAnnotations.MAX_NOTES] in all (about 3k chars at most).
 */
class DiffAnnotationsEnricher : AiContextEnricherModule {
    override val id: String = MODULE_ID
    override val name: String = "Diff Annotations"
    override val version: String = "1.0.0"
    override val description: String = "Flags removed guards and statements moved across conditions in the diff"
    override val dslName: String = "diffAnnotations"
    override val enricherPriority: Int = BLOCK_PRIORITY
    override val defaultEnabled: Boolean = true

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun shutdown() = Unit

    override suspend fun enrich(
        mr: MergeRequest,
        context: AiContextEnricherContext,
    ): Map<String, String> = emptyMap()

    override suspend fun provideBlock(
        mr: MergeRequest,
        changes: List<FileChange>,
        config: JsonElement,
        budget: BlockBudget,
    ): AiContextBlock? {
        if (!contextQualityFlag(config, CONFIG_KEYS, DiffAnnotationsConfig().enabled)) return null
        return blockFor(changes, budget)
    }

    companion object {
        const val MODULE_ID: String = "diff-annotations"
        private val CONFIG_KEYS: List<String> = listOf("diffAnnotations", "diff-annotations")
        const val BLOCK_PRIORITY: Int = 250
        const val BLOCK_HEADING: String = "#### Diff notes"
        private const val INTRO: String =
            "Computed from the diff without a model. Each note is a question to answer from the code, not a finding by itself:"

        /**
         * The notes on [changes] alone (one chunk's files when the diff is split, so a call never reads notes on hunks
         * it does not carry): removed guards of every file before any move, at most [DiffAnnotations.MAX_NOTES].
         */
        fun blockFor(
            changes: List<FileChange>,
            budget: BlockBudget,
        ): AiContextBlock? {
            val (guards, moves) = changes
                .filterNot { SensitiveFileFilter.isSensitive(it.path) }
                .map { DiffAnnotations.notes(it.path, it.diffContent) }
                .unzip()
            val notes = (guards.flatten() + moves.flatten()).take(DiffAnnotations.MAX_NOTES)
            if (notes.isEmpty()) return null
            val body = (listOf(INTRO) + notes.map { "- $it" }).joinToString("\n")
            return KnowledgeContextFormatter.formatBlock(BLOCK_HEADING, body, BLOCK_PRIORITY, MODULE_ID, budget)
        }
    }
}

/**
 * `contextQuality.<keys>.enabled` from the `ai:` plugin [config] (camelCase or kebab-case [keys]), false when
 * `contextQuality.enabled` is false; [default] when unset.
 */
internal fun contextQualityFlag(
    config: JsonElement,
    keys: List<String>,
    default: Boolean,
): Boolean {
    val contextQuality = (config as? JsonObject)?.let { it["contextQuality"] ?: it["context-quality"] } as? JsonObject ?: return default
    val master = (contextQuality["enabled"] as? JsonPrimitive)?.booleanOrNull ?: AiContextQualityConfig().enabled
    if (!master) return false
    val block = keys.firstNotNullOfOrNull { contextQuality[it] } as? JsonObject
    return (block?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: default
}

/** A `+`, `-` or context line of a hunk; [newLine] is its new-file line (for a `-` line, the new line that follows). */
@Serializable
internal data class DiffRow(
    val kind: Char,
    val newLine: Int,
    val text: String,
    /** Changed lines without a context line between them share a run; context lines have run 0. */
    val run: Int,
)

/** The heuristics behind [DiffAnnotationsEnricher], language-agnostic and line based. */
internal object DiffAnnotations {
    const val MAX_NOTES_PER_FILE: Int = 6
    const val MAX_NOTES: Int = 20
    private const val MAX_SNIPPET: Int = 120
    private const val MIN_STATEMENT_CHARS: Int = 8

    private val whitespace = Regex("""\s+""")
    private val conditionStart = Regex("""^(?:\}\s*)?(?:else\s+)?(?:if|guard|unless)\b""")
    private val assertionCall = Regex(
        """^(?:require|check|assert|precondition|ensure|checkNotNull|requireNotNull|assertNotNull|Preconditions\.\w+|""" +
            """DCHECK\w*|CHECK\w*|ASSERT\w*|assert_\w+)\s*\(""",
    )
    private val elvisExit = Regex("""\?:\s*(?:return|throw|break|continue)\b""")
    private val bareExit = Regex("""^(?:return(?:@\w+)?(?:\s+(?:null|nil|nullptr|false|true|0|-1))?|break|continue|throw\b.*)$""")
    private val featureCheck = Regex(
        """\b(?:is\w*Enabled|isFeature\w+|featureFlag\w*|FeatureFlag\w*|FeatureToggle\w*|hasPermission\w*|""" +
            """checkSelfPermission|isGranted|isAuthori[sz]ed)\b""",
    )
    private val boundary = Regex(
        """^(?:\}|(?:\}\s*)?(?:else|if|guard|unless|when|switch|case|for|while|catch|try)\b)""",
    )
    private val commentStart = Regex("""^(?://|/\*|\*|#|--|@|import\b|package\b)""")

    /** The notes for one file's unified [diff], at most [MAX_NOTES_PER_FILE], removed guards first. */
    fun annotate(
        path: String,
        diff: String?,
    ): List<String> = notes(path, diff).let { (guards, moves) -> guards + moves }

    /** One file's (removed guards, moves), together at most [MAX_NOTES_PER_FILE] with guards kept first. */
    fun notes(
        path: String,
        diff: String?,
    ): Pair<List<String>, List<String>> {
        val rows = rows(diff)
        if (rows.none { it.kind == '-' }) return emptyList<String>() to emptyList()
        val added = rows.withIndex().filter { it.value.kind == '+' }.groupBy { normalize(it.value.text) }
        val used = mutableSetOf<Int>()
        val guards = mutableListOf<String>()
        val moves = mutableListOf<String>()
        rows.withIndex().filter { it.value.kind == '-' }.forEach { (index, row) ->
            val key = normalize(row.text)
            val readded = added[key]
            when {
                key.isEmpty() -> Unit

                readded == null -> removedGuard(path, row, key)?.let(guards::add)

                else -> moved(path, rows, index, key, readded.filter { it.index !in used })?.let { (to, note) ->
                    used += to
                    moves += note
                }
            }
        }
        val keptGuards = guards.take(MAX_NOTES_PER_FILE)
        return keptGuards to moves.take(MAX_NOTES_PER_FILE - keptGuards.size)
    }

    private fun removedGuard(
        path: String,
        row: DiffRow,
        key: String,
    ): String? {
        val guard = conditionStart.containsMatchIn(key) || assertionCall.containsMatchIn(key) ||
            elvisExit.containsMatchIn(key) || bareExit.matches(key) || featureCheck.containsMatchIn(key)
        if (!guard) return null
        return "Removed guard at $path near L${row.newLine}: `${snippet(key)}` — what did it protect, and what happens now without it?"
    }

    /** The first candidate the deleted row at [index] moved to, as (row index, note); null when it was not moved. */
    private fun moved(
        path: String,
        rows: List<DiffRow>,
        index: Int,
        key: String,
        candidates: List<IndexedValue<DiffRow>>,
    ): Pair<Int, String>? {
        if (!isStatement(key)) return null
        val deleted = rows[index]
        return candidates.firstNotNullOfOrNull { (to, target) ->
            // Only unchanged lines count: a condition line that is itself rewritten moves with the block it opens
            val between = rows
                .subList(minOf(index, to) + 1, maxOf(index, to))
                .any { it.kind == ' ' && boundary.containsMatchIn(normalize(it.text)) }
            val sameRun = target.run == deleted.run
            when {
                sameRun && !between -> {
                    null
                }

                between -> {
                    to to "Moved across a condition or block in $path: `${snippet(key)}` from near L${deleted.newLine} to " +
                        "L${target.newLine} — does it now run in different cases or order?"
                }

                else -> {
                    to to "Moved in $path: `${snippet(key)}` from near L${deleted.newLine} to L${target.newLine} — " +
                        "does the new order change what it sees or does?"
                }
            }
        }
    }

    private fun isStatement(key: String): Boolean =
        key.length >= MIN_STATEMENT_CHARS && ('(' in key || '=' in key) && !commentStart.containsMatchIn(key)

    private fun normalize(text: String): String = text.trim().replace(whitespace, " ").trimEnd(';', ',', ' ')

    private fun snippet(key: String): String {
        val clean = key.replace('`', '\'')
        return if (clean.length <= MAX_SNIPPET) clean else clean.take(MAX_SNIPPET) + "…"
    }

    /** The hunk rows of a unified [diff]; lines before the first hunk header and `\` markers are skipped. */
    fun rows(diff: String?): List<DiffRow> {
        val out = mutableListOf<DiffRow>()
        var newLine = 0
        var run = 0
        var inRun = false
        var inHunk = false
        for (line in diff.orEmpty().lineSequence()) {
            val header = if (line.startsWith("@@")) HunkParser.HEADER_REGEX.find(line) else null
            val kind = line.firstOrNull()
            when {
                header != null -> {
                    newLine = header.groupValues[1].toIntOrNull() ?: 0
                    inHunk = true
                    inRun = false
                }

                !inHunk || kind == '\\' -> {
                    Unit
                }

                kind == '+' || kind == '-' -> {
                    if (!inRun) run++
                    inRun = true
                    out += DiffRow(kind, newLine, line.substring(1), run)
                    if (kind == '+') newLine++
                }

                else -> {
                    inRun = false
                    out += DiffRow(' ', newLine, line.drop(1), 0)
                    newLine++
                }
            }
        }
        return out
    }
}
