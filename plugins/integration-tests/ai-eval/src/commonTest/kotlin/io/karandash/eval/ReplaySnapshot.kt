package io.karandash.eval

import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.GoldenFiles
import kotlinx.io.files.Path

/**
 * Golden snapshots of [CaseReplay] under `eval/replay/<id>.md`, same mechanism as the cli compat
 * goldens ([GoldenFiles]): `-Dkarandash.updateGolden=true` rewrites them, otherwise the first differing line is reported.
 */
object ReplaySnapshot {
    fun render(replay: CaseReplay): String =
        buildString {
            appendLine("# ${replay.caseId}")
            appendLine()
            appendLine("## Counters")
            appendLine("```json")
            appendLine(KarandashJson.Pretty.encodeToString(ReplayCounters.serializer(), replay.counters))
            appendLine("```")
            replay.prompts.forEach { prompt ->
                appendLine()
                appendLine("## Prompt: ${prompt.analyzer}")
                appendLine("### System")
                appendLine(fenced(prompt.systemPrompt.orEmpty()))
                appendLine("### User")
                appendLine(fenced(prompt.prompt))
            }
            appendLine()
            appendLine("## Findings")
            replay.findings.forEach { appendLine("- ${it.severity} ${it.rule} ${it.file ?: "-"}:${it.line ?: "-"} ${it.message}") }
            appendLine()
            appendLine("## Summary")
            appendLine(fenced(replay.summary))
            replay.inlineNotes.forEach { note ->
                appendLine()
                appendLine("## Note: ${note.file}:${note.startLine?.let { "$it-" }.orEmpty()}${note.line}")
                appendLine(fenced(note.body))
            }
        }

    /** Returns null when [replay] matches its golden in [dir] (or it was regenerated), else a description. */
    fun check(
        dir: Path,
        replay: CaseReplay,
    ): String? = GoldenFiles.check(dir, "${replay.caseId}.md", render(replay))

    /** Tilde fence, so backtick code blocks inside rendered markdown stay inside. */
    private fun fenced(text: String): String = "$FENCE\n${text.trimEnd()}\n$FENCE"

    private const val FENCE = "~~~~~"
}
