package io.karandash.plugins.ai.core.context

import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.BlockBudget
import io.karandash.security.TicketContentRedactor

/**
 * Shared formatter for knowledge-enrichment context blocks.
 *
 * Applies [TicketContentRedactor] redaction and truncates to fit within the provided [BlockBudget]. The
 * `<untrusted-content>` boundary is added once, by the prompt assembler, around every enricher's block.
 * Used by [TicketContentEnricher], [DesignDocEnricher], [CiContextEnricher], and
 * [SymbolContextEnricher].
 *
 * Truncation is line-boundary-aware: the cut happens at the last newline before [BlockBudget.maxChars],
 * so the model never sees a partial line. A `[content truncated]` suffix is appended when truncated, and the
 * block's [AiContextBlock.cutFromChars] carries the length it was cut from.
 */
internal object KnowledgeContextFormatter {
    fun formatBlock(
        heading: String,
        content: String,
        priority: Int,
        source: String,
        budget: BlockBudget,
    ): AiContextBlock? {
        if (content.isBlank()) return null
        val redacted = TicketContentRedactor.redact(content)
        val bounded = truncateToBudget(redacted, budget.maxChars)
        return AiContextBlock(
            heading = heading,
            content = bounded,
            priority = priority,
            source = source,
            cutFromChars = redacted.length.takeIf { bounded != redacted },
        )
    }

    internal fun truncateToBudget(
        text: String,
        maxChars: Int
    ): String {
        if (maxChars == Int.MAX_VALUE || text.length <= maxChars) return text
        val candidate = text.take(maxChars)
        val cutPoint = candidate.lastIndexOf('\n')
        return if (cutPoint > 0) {
            candidate.take(cutPoint) + "\n[content truncated]"
        } else {
            candidate + "[content truncated]"
        }
    }
}
