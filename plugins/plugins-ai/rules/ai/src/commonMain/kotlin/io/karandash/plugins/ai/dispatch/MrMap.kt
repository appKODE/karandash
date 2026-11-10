package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiContextBlock
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.TokenUnit

/**
 * `ai.large-mr.mr-map-max-files`: tells a chunk's call which files of the MR other chunks carry, so the model does not
 * report as missing what another chunk adds and knows a change may have callers or callees there. A file whose hunks
 * are split across chunks is labelled `part k/n` in each of them.
 */
internal object MrMap {
    const val SOURCE = "mr-map"
    private const val PRIORITY = 85

    /**
     * [chunk] with the map of [all] files outside it and its split files' [parts] in its context, its tokens added to
     * the planned context.
     */
    fun forChunk(
        chunk: AiRuleDispatcher.DispatchInput,
        all: List<FileChange>,
        parts: Map<String, String> = emptyMap(),
    ): AiRuleDispatcher.DispatchInput {
        val block = block(chunk.mr.changes, all, chunk.pluginCfg.largeMr.mrMapMaxFiles, parts) ?: return chunk
        return chunk.copy(
            contextBlocks = chunk.contextBlocks + block,
            plannedContextTokens = chunk.plannedContextTokens + TokenUnit.tokensOf(block.heading + block.content),
        )
    }

    /** Tokens of the map a chunk's call carries at most: every file of [all] listed. */
    fun tokensOf(
        all: List<FileChange>,
        maxFiles: Int,
    ): Int = block(emptyList(), all, maxFiles)?.let { TokenUnit.tokensOf(it.heading + it.content) } ?: 0

    /** Path to `part k/n` for each file of chunk [index] of [chunks] whose hunks other chunks carry too. */
    fun parts(
        chunks: List<List<FileChange>>,
        index: Int,
    ): Map<String, String> =
        chunks[index]
            .map { it.path }
            .distinct()
            .mapNotNull { path ->
                val holders = chunks.indices.filter { i -> chunks[i].any { it.path == path } }
                if (holders.size < 2) null else path to "part ${holders.indexOf(index) + 1}/${holders.size}"
            }.toMap()

    fun block(
        chunk: List<FileChange>,
        all: List<FileChange>,
        maxFiles: Int,
        parts: Map<String, String> = emptyMap(),
    ): AiContextBlock? {
        val inChunk = chunk.mapTo(mutableSetOf()) { it.path }
        val others = all.filter { it.path !in inChunk }.distinctBy { it.path }
        if (maxFiles <= 0 || (others.isEmpty() && parts.isEmpty())) return null
        val listed = others.take(maxFiles).joinToString("\n") { "- ${it.path} (+${it.additions} -${it.deletions})" }
        val more = (others.size - maxFiles).takeIf { it > 0 }?.let { "\n- ... and $it more files" }.orEmpty()
        val split = parts.entries.joinToString("\n") { (path, part) -> "- $path ($part; other hunks are reviewed in another call)" }
        val splitNote = if (split.isEmpty()) "" else "\nFiles split across calls, only some of their hunks are here:\n$split"
        return AiContextBlock(
            heading = "#### Other files changed in this MR (reviewed in separate calls)",
            content = "This call reviews part of the MR. These files are changed too, in other calls: do not report " +
                "them or their changes as missing, and do not review them here.\n" + listed + more + splitNote,
            priority = PRIORITY,
            source = SOURCE,
        )
    }
}
