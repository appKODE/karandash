package io.karandash.plugins.formats

import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.skipReasonIn
import io.karandash.utils.takeWhole

/** Diagrams with more nodes than this render as a stage table instead: Mermaid gets unreadable past it. */
internal const val FLOW_MAX_NODES = 24

private val UNSAFE_LABEL_CHARS = Regex("""["\[\]<>{}|()#;`]""")
private val WHITESPACE = Regex("\\s+")

/** Longest node label; a longer one (a long rule id or reason) is cut with an ellipsis. */
internal const val FLOW_LABEL_MAX = 80

/**
 * The run as a Mermaid `flowchart LR`: input, enrichers, context, analyzers, filters, output. Edges into and
 * between the filters carry the finding count at that point; nodes are classed `ok`, `skip` or `fail`. Over
 * [FLOW_MAX_NODES] nodes, a `| Stage | Findings |` table carries the same counts. The fixed words are in [locale];
 * ids, models, reasons and filter stage names are never translated.
 */
internal fun flowDiagram(
    provenance: ReviewProvenance,
    locale: SummaryLocale = SummaryLocale.EN,
): String {
    val nodeCount = 3 + provenance.enrichers.size + provenance.analyzers.size + provenance.filters.size
    val stageCounts = stageCounts(provenance, locale)
    if (nodeCount > FLOW_MAX_NODES) {
        return buildString {
            append("${locale.t(SummaryText.FLOW_TABLE_HEADER)}\n|---|---|\n")
            stageCounts.forEach { (stage, count) -> append("| ${sanitizeMarkdown(stage)} | $count |\n") }
        }
    }
    val graph = FlowGraph()
    val diff = locale.t(SummaryText.FLOW_MR_DIFF)
    val input = graph.node(provenance.coverage?.describe(locale.tag)?.let { "$diff: $it" } ?: diff, ProvenanceStatus.OK)
    val context = graph.node(locale.t(SummaryText.FLOW_CONTEXT), ProvenanceStatus.OK)
    if (provenance.enrichers.isEmpty()) graph.edge(input, context)
    provenance.enrichers.forEach { e ->
        val node = graph.node(
            locale.t(SummaryText.FLOW_ENRICHER, e.id) + (e.reason?.let { ": ${skipReasonIn(it, locale.tag)}" } ?: ""),
            e.status
        )
        graph.edge(input, node)
        graph.edge(node, context)
    }
    val firstFilter = graph.peekNext() + provenance.analyzers.size
    val on = locale.t(SummaryText.FLOW_ON_MODEL)
    provenance.analyzers.forEach { a ->
        val detail = a.model?.let { " $on $it" } ?: a.reason?.let { ": ${skipReasonIn(it, locale.tag)}" } ?: ""
        val node = graph.node(locale.t(SummaryText.FLOW_ANALYZER, a.ruleId) + detail, a.status)
        graph.edge(context, node)
        graph.edge(node, firstFilter, a.findings)
    }
    if (provenance.analyzers.isEmpty()) graph.edge(context, firstFilter)
    provenance.filters.zip(stageCounts.drop(1)).forEach { (f, left) ->
        val node = graph.node(locale.t(SummaryText.FLOW_FILTER, f.stage) + ": -${f.removed}", ProvenanceStatus.OK)
        graph.edge(node, graph.peekNext(), left.second)
    }
    graph.node("${locale.t(SummaryText.FLOW_POSTED)}: ${stageCounts.last().second}", ProvenanceStatus.OK)
    return graph.render()
}

/** Findings left after each stage: all analyzers, then each filter, then what was posted. */
private fun stageCounts(
    provenance: ReviewProvenance,
    locale: SummaryLocale,
): List<Pair<String, Int>> {
    val total = provenance.analyzers.sumOf { it.findings }
    val afterFilters = provenance.filters.runningFold(locale.t(SummaryText.FLOW_ANALYZERS) to total) { (_, left), f ->
        f.stage to left - f.removed
    }
    return afterFilters + (locale.t(SummaryText.FLOW_POSTED) to afterFilters.last().second)
}

/** @lifecycle per-run -- one diagram per call; ids are the node index so labels never need escaping as ids. */
private class FlowGraph {
    private val nodes = mutableListOf<String>()
    private val edges = mutableListOf<String>()

    fun peekNext(): Int = nodes.size

    fun node(
        label: String,
        status: ProvenanceStatus
    ): Int {
        val safe = label
            .replace(UNSAFE_LABEL_CHARS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .let { if (it.length > FLOW_LABEL_MAX) it.takeWhole(FLOW_LABEL_MAX - 1) + "\u2026" else it }
        nodes += "  n${nodes.size}[\"$safe\"]:::${status.cssClass()}"
        return nodes.size - 1
    }

    fun edge(
        from: Int,
        to: Int,
        count: Int? = null
    ) {
        edges += "  n$from -->" + (count?.let { "|$it|" } ?: "") + " n$to"
    }

    fun render(): String =
        buildString {
            append("```mermaid\nflowchart LR\n")
            (nodes + edges).forEach { append(it).append('\n') }
            append("  classDef ok fill:#e6f4ea,stroke:#1e8e3e\n")
            append("  classDef skip fill:#f1f3f4,stroke:#9aa0a6,stroke-dasharray:3 3\n")
            append("  classDef fail fill:#fce8e6,stroke:#d93025\n")
            append("```\n")
        }
}

private fun ProvenanceStatus.cssClass(): String =
    when (this) {
        ProvenanceStatus.OK -> "ok"
        ProvenanceStatus.SKIPPED -> "skip"
        ProvenanceStatus.FAILED -> "fail"
    }
