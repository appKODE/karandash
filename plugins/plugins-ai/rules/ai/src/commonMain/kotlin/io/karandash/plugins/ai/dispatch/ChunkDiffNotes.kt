package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.BlockBudget
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.context.DiffAnnotationsEnricher

/**
 * Diff notes are built once per rule for the whole MR; a chunk's call gets them rebuilt from its own files, so it
 * never reads notes on hunks it does not carry. The rebuilt block fits the space the planned one took.
 */
internal object ChunkDiffNotes {
    fun rescope(chunk: AiRuleDispatcher.DispatchInput): AiRuleDispatcher.DispatchInput {
        val index = chunk.contextBlocks.indexOfFirst { it.source == DiffAnnotationsEnricher.MODULE_ID }
        if (index < 0) return chunk
        val planned = chunk.contextBlocks[index]
        val overhead = wrapUntrusted(planned.copy(heading = DiffAnnotationsEnricher.BLOCK_HEADING, content = "")).length
        val rebuilt = DiffAnnotationsEnricher
            .blockFor(chunk.mr.changes, BlockBudget((planned.content.length - overhead).coerceAtLeast(0)))
            ?.takeIf { it.content.isNotBlank() }
            ?.let { planned.copy(content = wrapUntrusted(it), cutFromChars = null) }
        val blocks = chunk.contextBlocks.toMutableList().apply { if (rebuilt == null) removeAt(index) else set(index, rebuilt) }
        val delta = TokenUnit.tokensOf(rebuilt?.content.orEmpty()) - TokenUnit.tokensOf(planned.content)
        return chunk.copy(contextBlocks = blocks, plannedContextTokens = (chunk.plannedContextTokens + delta).coerceAtLeast(0))
    }
}
