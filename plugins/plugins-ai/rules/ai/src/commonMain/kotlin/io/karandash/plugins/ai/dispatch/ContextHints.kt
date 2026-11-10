@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiContextBlock
import io.karandash.api.run.dumpJson
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.TokenUnit
import kotlinx.serialization.Serializable

/**
 * `ai.hints`: facts about the MR the diff alone hides, put first in a review-comments call's context, at most
 * `ai.hints.maxTokens`: (a) declarations a `-` line removes that some MR file still declares (moved, not gone), and
 * (b) where in the MR the identifiers the call's diff uses are declared, when another file declares them. Built from
 * the text only, so the same MR always gets the same hints. Dumped as `ai/stages/hints.json`.
 */
internal object ContextHints {
    const val SOURCE = "context-hints"
    private const val PRIORITY = 95
    private const val MIN_NAME_LENGTH = 3

    // limit: keyword-led declarations of common languages only; a symbol index would also catch the rest
    private val DECLARATION = Regex(
        """\b(?:fun|class|interface|object|enum|struct|trait|typealias|def|function|func|fn)\s+""" +
            """(?:<[^>]*>\s*)?(?:[A-Za-z_]\w*\.)?([A-Za-z_]\w*)"""
    )
    private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

    /** The hint lines one call got, for the dump. */
    @Serializable
    data class Hints(
        val rule: String,
        val files: List<String>,
        val lines: List<String>,
        val left: Int,
        val tokens: Int,
    )

    /** [call] with the hints of its diff against [all] MR files in its context, their tokens added to the plan. */
    suspend fun forCall(
        call: AiRuleDispatcher.DispatchInput,
        all: List<FileChange>,
    ): AiRuleDispatcher.DispatchInput {
        val cfg = call.pluginCfg.hints
        if (call.ruleId != AiRuleIds.REVIEW_COMMENTS || !cfg.enabled) return call
        val lines = lines(call.mr.changes, all)
        if (lines.isEmpty()) return call
        val kept = lines
            .runningFold("") { acc, line -> "$acc\n$line" }
            .drop(1)
            .takeWhile { TokenUnit.tokensOf(HEADING + it) <= cfg.maxTokens }
        val content = kept.lastOrNull()?.trimStart() ?: return call
        val tokens = TokenUnit.tokensOf(HEADING + content)
        dumpJson("ai/stages/hints.json") { Hints(call.ruleId, call.mr.changes.map { it.path }, lines, lines.size - kept.size, tokens) }
        return call.copy(
            contextBlocks = call.contextBlocks + AiContextBlock(HEADING, content, PRIORITY, SOURCE),
            plannedContextTokens = call.plannedContextTokens + tokens,
        )
    }

    private const val HEADING = "#### Facts about this MR (checked from its files)"

    /** The hint lines for the [diff] files of a call over [all] MR files: moved declarations first, then definitions. */
    fun lines(
        diff: List<FileChange>,
        all: List<FileChange>,
    ): List<String> {
        val declaredNow = all.associate { it.path to declared(it.newContent.orEmpty()) }
        val moved = diff.flatMap { change ->
            declared(changed(change, '-')).sorted().mapNotNull { name ->
                val holders = declaredNow.filter { name in it.value }.keys.sorted()
                if (holders.isEmpty()) null else "- `$name` removed in ${change.path} is still declared in ${holders.joinToString()}"
            }
        }
        val added = all.associate { it.path to declared(changed(it, '+')) }
        val defined = diff
            .flatMap { change ->
                val kept = change.diffContent
                    .orEmpty()
                    .lineSequence()
                    .filterNot { it.startsWith('-') }
                    .joinToString("\n")
                val used = IDENTIFIER.findAll(kept).map { it.value }.toSet()
                added.filterKeys { it != change.path }.flatMap { (path, names) -> (names intersect used).map { it to path } }
            }.groupBy({ it.first }, { it.second })
            .entries
            .sortedBy { it.key }
            .map { (name, paths) -> "- `$name`, used in this diff, is declared in ${paths.distinct().sorted().joinToString()}" }
        return (moved + defined).distinct()
    }

    private fun declared(text: String): Set<String> =
        DECLARATION
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { it.length >= MIN_NAME_LENGTH }
            .toSet()

    /** The [sign] (`+` or `-`) lines of [change]'s diff without the sign; the file headers left out. */
    private fun changed(
        change: FileChange,
        sign: Char,
    ): String =
        change.diffContent
            .orEmpty()
            .lineSequence()
            .filter { it.startsWith(sign) && !it.startsWith("$sign$sign$sign") }
            .joinToString("\n") { it.drop(1) }
}
