package io.karandash.api.module

import io.karandash.api.finding.ReviewProvenance
import io.karandash.api.finding.SkippedRuleInfo

/**
 * Optional interface a [RuleModule] may implement to report skip reasons
 * for rules it internally decided not to execute.
 *
 * The validation engine queries this after rule execution to merge
 * module-internal skip reasons (e.g., AI budget exhaustion, gating)
 * into the [io.karandash.api.finding.ValidationReport].
 */
public interface SkipReasonProvider {
    /**
     * Returns skip info for rules this module decided to skip internally.
     *
     * Keys are rule IDs. Called once after all rule execution completes.
     */
    public fun skippedRuleReasons(): Map<String, SkippedRuleInfo>

    /**
     * What this module's rules actually reviewed (files, skipped and truncated chunks); null when it reviewed nothing.
     * Called once after all rule execution completes, like [skippedRuleReasons].
     */
    public fun reviewProvenance(): ReviewProvenance? = null

    /**
     * Every rule the engine itself skipped this run (policy, bail-on-fail, `when`, missing enrichment), before
     * [skippedRuleReasons] is read. A module whose rules never reached it can tell "off by policy" from "not run";
     * the engine still reports these skips itself, so a module must not report them again. Default: ignored.
     */
    public fun onEngineSkips(skips: List<SkippedRuleInfo>) {}
}
