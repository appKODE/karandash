package io.karandash.plugins.ai.dispatch

private const val PERCENT = 100L

/**
 * The plan's one line before any rule calls the model: calls and tokens, and when a rule is short, how much of its
 * need each short rule has and the [knob] value ([wanted] tokens) that funds every rule in full.
 */
internal fun planSummary(
    rules: List<RuleSizing>,
    runBudget: Int,
    wanted: Long,
    knob: String,
): String {
    val calls = rules.filter { it.skipReason == null }.sumOf { it.calls }
    val head = "[ai] budget plan: $calls calls, $runBudget tokens"
    val short = rules.filter { it.need > it.share + it.grant }
    if (short.isEmpty()) return "$head, every rule funded in full"
    val funded = short.joinToString { "${it.rule} ${(it.share + it.grant) * PERCENT / it.need}%" }
    return "$head of the $wanted this MR needs; funded: $funded; raise $knob to $wanted to review it all"
}
