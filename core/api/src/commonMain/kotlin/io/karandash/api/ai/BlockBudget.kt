package io.karandash.api.ai

import kotlinx.serialization.Serializable

/**
 * Budget hint passed to [AiContextEnricherModule.provideBlock].
 *
 * Enrichers should self-truncate their block content so that it stays within
 * [maxChars]. Truncation is done atomically at semantic boundaries (e.g., end
 * of a list item or sentence), never mid-word. A block that exceeds the budget
 * after self-truncation is dropped entirely by the assembler.
 *
 * @param maxChars Maximum character count for the formatted block content.
 *   Use [UNLIMITED] (Int.MAX_VALUE) when no budget applies.
 */
@Serializable
public data class BlockBudget(
    val maxChars: Int
) {
    public companion object {
        /** No budget limit - include the full block. */
        public val UNLIMITED: BlockBudget = BlockBudget(maxChars = Int.MAX_VALUE)
    }
}
