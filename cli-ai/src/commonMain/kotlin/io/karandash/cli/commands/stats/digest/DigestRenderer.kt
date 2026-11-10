package io.karandash.cli.commands.stats.digest

import kotlin.math.roundToLong

/** Prints a [Digest] as plain text or as a Markdown table; a metric with nothing behind it reads `n/a`. */
object DigestRenderer {
    private const val NA = "n/a"
    private const val PERMILLE = 1000.0
    private const val TENTHS = 10.0
    private const val MS_PER_TENTH = 100.0
    private const val COST_DIGITS = 10_000.0
    private const val LABEL_WIDTH = 28

    fun rows(d: Digest): List<Pair<String, String>> =
        listOf(
            "Runs" to "${d.runs} (${d.mergeRequests} merge requests)",
            "Red runs" to percent(d.redRunRate),
            "AI availability" to "${percent(d.aiAvailability)} of ${d.aiRuns} AI runs",
            "Fix rate" to "${percent(d.fixRate)} over ${d.fixRateMergeRequests} merge requests with 2+ runs",
            "Persistence" to percent(d.fixRate?.let { 1 - it }),
            "AI cost" to "${usd(d.aiCostUsd)} total, ${d.aiCostPerMergeRequest?.let(::usd) ?: NA} per merge request",
            "Cache hit" to "${percent(d.cacheHitRate)} (rules from cache ${percent(d.ruleCacheHitRate)})",
            "Duration" to "p50 ${seconds(d.durationP50Ms)}, p95 ${seconds(d.durationP95Ms)}",
            "False positives" to "${d.falsePositives}",
            "Dead rules" to d.deadRules.ifEmpty { listOf("none") }.joinToString(", "),
        ) + if (d.skippedFiles > 0) listOf("Skipped files" to "${d.skippedFiles}") else emptyList()

    fun text(d: Digest): String = rows(d).joinToString("\n") { (k, v) -> "${k.padEnd(LABEL_WIDTH)}$v" }

    fun markdown(d: Digest): String =
        (
            listOf("## karandash digest", "", "| Metric | Value |", "| --- | --- |") +
                rows(d).map { (k, v) -> "| $k | ${v.replace("|", "\\|")} |" }
        ).joinToString("\n")

    private fun percent(rate: Double?): String = rate?.let { "${(it * PERMILLE).roundToLong() / TENTHS}%" } ?: NA

    private fun usd(v: Double): String = "$" + ((v * COST_DIGITS).roundToLong() / COST_DIGITS)

    private fun seconds(ms: Long?): String = ms?.let { "${(it / MS_PER_TENTH).roundToLong() / TENTHS}s" } ?: NA
}
