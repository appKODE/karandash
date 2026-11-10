package io.karandash.plugins.ai.analyzers.mrsummary

import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.ReviewUnits
import kotlinx.serialization.Serializable

/** One call between review units the model saw in the diff, drawn as a sequence diagram message. */
@Serializable
internal data class MrSummaryInteraction(
    val from: String,
    val to: String,
    val message: String = "",
)

private const val MAX_FILES_PER_UNIT = 5
private const val MAX_MESSAGES = 12
private const val SAFE_DIAGRAM_PUNCTUATION = " ._/-"

/**
 * The walkthrough: a table of [changes] by review unit (files and lines changed, riskiest unit first), then a
 * Mermaid `sequenceDiagram` of [interactions] between distinct units when two or more units interact. Null when
 * the MR has no changes.
 */
internal fun walkthrough(
    changes: List<FileChange>,
    interactions: List<MrSummaryInteraction>,
): String? {
    if (changes.isEmpty()) return null
    return buildString {
        append("| Unit | Files | Lines |\n|---|---|---|\n")
        ReviewUnits.group(changes).forEach { unit ->
            val prefix = if (unit.name == ReviewUnits.ROOT) "" else "${unit.name}/"
            val shown = unit.changes.take(MAX_FILES_PER_UNIT).joinToString("<br>") { "`${cell(it.path.removePrefix(prefix))}`" }
            val more = (unit.changes.size - MAX_FILES_PER_UNIT).takeIf { it > 0 }?.let { "<br>and $it more" }.orEmpty()
            val added = unit.changes.sumOf { it.additions }
            val deleted = unit.changes.sumOf { it.deletions }
            append("| `${cell(unit.name)}` | $shown$more | +$added/-$deleted |\n")
        }
        sequenceDiagram(interactions)?.let { append('\n').append(it) }
    }
}

// limit: the first MAX_MESSAGES calls only; a larger MR's diagram is unreadable anyway.
private fun sequenceDiagram(interactions: List<MrSummaryInteraction>): String? {
    val calls = interactions
        .map { MrSummaryInteraction(diagramText(it.from), diagramText(it.to), diagramText(it.message)) }
        .filter { it.from.isNotEmpty() && it.to.isNotEmpty() && it.from != it.to }
        .take(MAX_MESSAGES)
    if (calls.isEmpty()) return null
    val participants = calls.flatMap { listOf(it.from, it.to) }.distinct()
    val alias = participants.withIndex().associate { (i, name) -> name to "u$i" }
    return buildString {
        append("```mermaid\nsequenceDiagram\n")
        participants.forEach { append("  participant ${alias[it]} as $it\n") }
        calls.forEach { append("  ${alias[it.from]}->>${alias[it.to]}: ${it.message.ifEmpty { "calls" }}\n") }
        append("```\n")
    }
}

/** Mermaid-safe text: anything but letters, digits, spaces and `._/-` becomes a space, whitespace collapsed. */
private fun diagramText(text: String): String =
    text
        .map { if (it.isLetterOrDigit() || it in SAFE_DIAGRAM_PUNCTUATION) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/**
 * Table-cell-safe text for an MR path or unit name (attacker-controlled). The markdown format's `sanitizeMarkdown` is
 * internal to a plugins-core module this AI analyzer must not depend on, so this escapes locally: newlines become a
 * space (a newline would end the table row), `&` `<` `>` become entities, `\` and `|` are escaped (a bare `\` before
 * `|` would unescape it) and a backtick becomes `'`.
 */
private fun cell(text: String): String =
    text
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("`", "'")
