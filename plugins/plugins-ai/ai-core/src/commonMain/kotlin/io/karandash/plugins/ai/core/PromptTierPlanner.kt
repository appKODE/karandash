package io.karandash.plugins.ai.core

import kotlinx.serialization.Serializable

/**
 * One named section of a rule's prompt. T2 context is filled by [tier] (most valuable first), then by [priority]
 * (highest first) within a tier; a part cut to fit keeps its last [keepTail] characters (a closing tag).
 */
@Serializable
data class PromptPart(
    val name: String,
    val text: String,
    val priority: Int = 0,
    val tier: ContextTier = ContextTier.CALLERS_RELATED,
    val keepTail: Int = 0,
)

/**
 * The fill order of T2 context under `ai.context.budget`, most valuable first. The diff hunks come before all of
 * them and are never cut for context; the last tier is the first cut or dropped.
 */
@Serializable
enum class ContextTier {
    DELETED_CODE,
    ENCLOSING_FUNCTION,
    SYMBOL_DEFINITIONS,
    CALLERS_RELATED,
    RELATED_TESTS,
    CONVENTIONS_DOCS,
}

/** Estimated tokens of one [tier] the plan kept, cut away from a part it shortened, and dropped with whole parts. */
@Serializable
data class TierUsage(
    val tier: ContextTier,
    val kept: Int = 0,
    val cut: Int = 0,
    val dropped: Int = 0,
)

/** The outcome of [PromptTierPlanner.plan]. */
@Serializable
sealed interface TierPlan {
    /**
     * T0 whole, the diff gets [diffTokens], [kept] context in input order (a part in [cut] shortened to fit),
     * [dropped] context names, and per-tier token counts in [tiers] (only tiers that had a part).
     */
    @Serializable
    data class Fits(
        val t0: List<PromptPart>,
        val kept: List<PromptPart>,
        val dropped: List<String>,
        val diffTokens: Int,
        val cut: List<String> = emptyList(),
        val tiers: List<TierUsage> = emptyList(),
    ) : TierPlan

    /** T0 alone is over [maxInputTokens], or fills it and leaves the diff nothing; [item] is its largest part, [itemTokens] that part's size. */
    @Serializable
    data class T0OverBudget(
        val item: String,
        val itemTokens: Int,
        val t0Tokens: Int,
        val maxInputTokens: Int,
    ) : TierPlan
}

/**
 * Splits a rule's input-token budget across the three prompt tiers:
 * - T0 (system prompt, output contract, project instructions, MR and ticket text) is never cut; when it alone
 *   exceeds the budget (or fills it and leaves a wanted diff nothing) the plan fails fast and names the largest item.
 * - T1, the diff, takes what T0 leaves, up to what it wants.
 * - T2, context, fills the remainder in [ContextTier] order, then by priority. From the first part that does not
 *   fit on, the remaining parts share the room left equally: one under its share stays whole and hands the rest on,
 *   a larger one is cut to its share (at a line boundary, keeping its tail) when that is at least [MIN_CUT_TOKENS],
 *   else dropped. So the full-file context cannot crowd symbol definitions out of every chunk. Nothing is cut when
 *   the budget covers it all.
 *
 * A part of several `### file` sections (the full-file context) is cut per section, each keeping the top of an equal
 * share of the room (a short section hands its unused share on), not the first files whole and the rest dropped.
 *
 * A [splittableDiff] (one the dispatcher chunks to whatever size the diff gets) that is chunked anyway, being over
 * the room T0 leaves, is guaranteed only half that room; T2 is fitted into the rest first and the diff takes what
 * T2 left, so every chunk carries the context instead of the diff crowding it out of every call. A diff that fits
 * whole keeps priority, so context never turns a one-call review into chunks.
 */
object PromptTierPlanner {
    /** Below this many tokens of room a part is dropped rather than cut to a stub. */
    const val MIN_CUT_TOKENS = 100

    /** Line put where a part was cut. */
    const val CUT_MARKER = "[cut to fit ai.context.budget]"

    fun plan(
        maxInputTokens: Int,
        t0: List<PromptPart>,
        t2: List<PromptPart>,
        diffTokensWanted: Int,
        splittableDiff: Boolean = false,
    ): TierPlan {
        val t0Tokens = tokensOf(t0)
        // T0 that fills the cap leaves no room for a diff that is there to review
        if (t0Tokens > maxInputTokens || (t0Tokens == maxInputTokens && diffTokensWanted > 0)) {
            val largest = t0.maxBy { tokensOf(it) }
            return TierPlan.T0OverBudget(largest.name, tokensOf(largest), t0Tokens, maxInputTokens)
        }
        val afterT0 = maxInputTokens - t0Tokens
        val chunked = splittableDiff && diffTokensWanted > afterT0
        val reserved = diffTokensWanted.coerceIn(0, diffRoom(afterT0, chunked))
        val fill = fillT2(t2, afterT0 - reserved)
        val diffTokens = diffTokensWanted.coerceIn(0, reserved + fill.left)
        val kept = t2.mapNotNull { fill.kept[it.name] }
        val dropped = t2.filter { it.name !in fill.kept }.map { it.name }
        return TierPlan.Fits(t0, kept, dropped, diffTokens, fill.cut, tierUsage(t2, fill.kept))
    }

    /** The diff a call guarantees out of the [room] T0 and the output reserve leave: half when [halve], so T2 context fits too. */
    fun diffRoom(
        room: Int,
        halve: Boolean,
    ): Int = if (halve) room / 2 else room

    /** Estimated tokens of [parts], each rounded up on its own. */
    fun tokensOf(parts: List<PromptPart>): Int = parts.sumOf { tokensOf(it) }

    private fun tokensOf(part: PromptPart): Int = TokenUnit.tokensOf(part.text)

    private class Fill(
        val kept: Map<String, PromptPart>,
        val cut: List<String>,
        val left: Int,
    )

    private fun fillT2(
        t2: List<PromptPart>,
        room: Int,
    ): Fill {
        val kept = mutableMapOf<String, PromptPart>()
        val overflow = mutableListOf<PromptPart>()
        var left = room
        for (part in t2.sortedWith(compareBy<PromptPart> { it.tier.ordinal }.thenByDescending { it.priority })) {
            val size = tokensOf(part)
            if (overflow.isEmpty() && size <= left) {
                kept[part.name] = part
                left -= size
            } else {
                overflow += part
            }
        }
        // From the first part that does not fit on, the parts share what is left equally (the shortest first,
        // handing on its unused share), so one large tier such as the full-file context cannot starve the rest.
        val cut = mutableListOf<String>()
        overflow.sortedBy { tokensOf(it) }.forEachIndexed { rank, part ->
            val share = left / (overflow.size - rank)
            val fitted = if (tokensOf(part) <= share) part else cutTo(part, share)?.also { cut += part.name }
            fitted?.let {
                kept[part.name] = it
                left -= tokensOf(it)
            }
        }
        return Fill(kept, cut, left)
    }

    /**
     * [part] shortened to at most [room] tokens at a line boundary, its tail and a [CUT_MARKER] kept; null when too small.
     * A part of several `### file` sections is cut per section so each keeps a share, else the head is kept.
     */
    private fun cutTo(
        part: PromptPart,
        room: Int,
    ): PromptPart? {
        if (room < MIN_CUT_TOKENS) return null
        val tail = part.text.takeLast(part.keepTail.coerceIn(0, part.text.length))
        val body = part.text.dropLast(tail.length)
        val sections = splitSections(body)
        val suffix = "\n$CUT_MARKER$tail"
        var chars = TokenUnit.tokensToChars(room) - suffix.length
        while (chars > 0) {
            val text = (sections?.let { cutSections(it, chars, tail) } ?: cutHead(body, chars, suffix))
                ?: return null
            if (TokenUnit.tokensOf(text) <= room) return part.copy(text = text)
            // non-ASCII text counts denser than chars/4: shrink until the estimate fits
            chars = minOf(text.length, chars) * SHRINK_NUMERATOR / SHRINK_DENOMINATOR
        }
        return null
    }

    private fun cutHead(
        body: String,
        chars: Int,
        suffix: String,
    ): String? {
        val raw = body.take(chars)
        val head = raw.substringBeforeLast('\n', raw).trimEnd()
        return if (head.isEmpty()) null else head + suffix
    }

    /** The text before the first `### ` line and the `### ` sections of [body]; null with fewer than two sections. */
    private fun splitSections(body: String): Pair<String, List<List<String>>>? {
        val lines = body.split('\n')
        val starts = lines.indices.filter { lines[it].startsWith(SECTION_HEADER) }
        if (starts.size < 2) return null
        val sections = starts.mapIndexed { i, start ->
            lines.subList(start, starts.getOrElse(i + 1) { lines.size }).dropLastWhile { it.isBlank() }
        }
        return lines.take(starts.first()).joinToString("\n") to sections
    }

    /**
     * The sections cut to about [chars] characters in all: each gets an equal share of what is left, a short one
     * returns its unused share to the rest, so no file is dropped for the size of the files before it.
     */
    private fun cutSections(
        split: Pair<String, List<List<String>>>,
        chars: Int,
        tail: String,
    ): String? {
        val (prefix, sections) = split
        var left = chars - prefix.length - tail.length - (sections.size - 1) * SECTION_SEPARATOR.length
        val sizes = sections.map { it.sumOf { line -> line.length + 1 } }
        val order = sections.indices.sortedBy { sizes[it] }
        val cutSections = arrayOfNulls<List<String>>(sections.size)
        order.forEachIndexed { rank, i ->
            val share = left / (order.size - rank)
            cutSections[i] = if (sizes[i] <= share) sections[i] else trimSection(sections[i], share)
            left -= cutSections[i]?.sumOf { it.length + 1 } ?: 0
        }
        val kept = cutSections.filterNotNull().map { it.joinToString("\n") }
        if (kept.isEmpty()) return null
        val dropped = if (kept.size < sections.size) SECTION_SEPARATOR + CUT_MARKER else ""
        return prefix + kept.joinToString(SECTION_SEPARATOR) + dropped + tail
    }

    /**
     * [lines] of one section (header, opening fence, body, closing fence) with body lines from the top kept up to
     * [share] characters and a [CUT_MARKER] line before the closing fence; null when not even one body line fits.
     */
    private fun trimSection(
        lines: List<String>,
        share: Int,
    ): List<String>? {
        val fenced = lines.size > 2 && lines[1].startsWith(FENCE)
        val top = if (fenced) lines.take(2) else lines.take(1)
        val bottom = if (fenced && lines.last() == FENCE) listOf(CUT_MARKER, FENCE) else listOf(CUT_MARKER)
        val bodyLines = lines.drop(top.size).let { if (bottom.size == 2) it.dropLast(1) else it }
        var used = (top + bottom).sumOf { it.length + 1 }
        val kept = bodyLines.takeWhile { line ->
            used += line.length + 1
            used <= share
        }
        return if (kept.isEmpty()) null else top + kept + bottom
    }

    private fun tierUsage(
        t2: List<PromptPart>,
        kept: Map<String, PromptPart>,
    ): List<TierUsage> =
        t2
            .groupBy { it.tier }
            .let { groups ->
                ContextTier.entries.mapNotNull { tier -> groups[tier]?.let { tier to it } }
            }.map { (tier, parts) ->
                parts.fold(TierUsage(tier)) { usage, part ->
                    val full = tokensOf(part)
                    val keptTokens = kept[part.name]?.let { tokensOf(it) }
                    when {
                        keptTokens == null -> usage.copy(dropped = usage.dropped + full)
                        else -> usage.copy(kept = usage.kept + keptTokens, cut = usage.cut + (full - keptTokens).coerceAtLeast(0))
                    }
                }
            }

    private const val SECTION_HEADER = "### "
    private const val SECTION_SEPARATOR = "\n\n"
    private const val FENCE = "```"
    private const val SHRINK_NUMERATOR = 9
    private const val SHRINK_DENOMINATOR = 10
}
