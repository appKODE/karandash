package io.karandash.api.ai

import kotlinx.serialization.Serializable

/**
 * A block of structured context injected into AI prompts by an
 * [AiContextEnricherModule.provideBlock] implementation.
 *
 * Unlike the flat key/value map returned by [AiContextEnricherModule.enrich],
 * a block is a self-contained heading + body pair with a priority so the prompt
 * assembler can order multiple blocks and drop ones that do not fit the budget.
 *
 * Blocks are atomic: the assembler includes the full block or skips it - there
 * is no partial truncation of a block's body. An enricher that cut its own body
 * to the [BlockBudget] it was given says so in [cutFromChars].
 *
 * @property heading Short Markdown heading shown above [content] (e.g.
 *     `"#### Related files for src/Auth.kt"`). Used verbatim in the prompt.
 * @property content Body text - usually a handful of lines of plain text or
 *     fenced code. The assembler appends this directly after [heading].
 * @property priority Higher values win when the assembler has to choose which
 *     blocks to include within the total context budget.
 * @property source Identifier of the enricher that produced the block (used
 *     for telemetry and debug logs - not shown to the AI).
 * @property cutFromChars Length of the body before the enricher cut it to its
 *     [BlockBudget]; the coverage ledger names the cut. Null when nothing was cut.
 */
@Serializable
public data class AiContextBlock(
    val heading: String,
    val content: String,
    val priority: Int,
    val source: String,
    val cutFromChars: Int? = null,
) {
    /** Approximate character cost of including this block (heading + newline + content + newline). */
    val charCount: Int
        get() = heading.length + 1 + content.length + 1
}
