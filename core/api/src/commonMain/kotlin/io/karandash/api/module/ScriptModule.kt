package io.karandash.api.module

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.serialization.json.JsonElement

/**
 * Module interface for script-based merge request validation.
 *
 * Scripts run user-authored JavaScript (or other script languages) against the
 * current [MergeRequest], producing [Finding] results that are merged with
 * rule-based findings.
 *
 * Two execution modes are supported:
 * - **Sandbox** - scripts run in an embedded QuickJS engine with no filesystem/network access.
 * - **Subprocess** - scripts run via Node.js subprocess with full system access.
 *
 * The `scripts:` top-level config block maps to this module:
 * ```yaml
 * scripts:
 *   - path: .karandash/checks.js
 *   - path: .karandash/security.js
 *     mode: subprocess
 *     timeout: 15000
 * ```
 *
 * @see Module The parent interface for all structured plugins
 */
public interface ScriptModule : Module {
    override val type: ModuleType get() = ModuleType.SCRIPT

    /**
     * Execute all configured scripts against the merge request and return combined findings.
     *
     * @param scripts Raw script entry configs from the `scripts:` config block as [JsonElement] entries.
     *   Each element contains `path` (required), optional `mode` ("sandbox"/"subprocess"/"auto"),
     *   and optional `timeout` (millis).
     * @param mr The current merge request under validation.
     * @param context Plugin context providing logging, env access, and working directory.
     * @return Combined list of findings from all script executions.
     */
    public suspend fun execute(
        scripts: List<JsonElement>,
        mr: MergeRequest,
        context: PluginContext,
    ): List<Finding>
}
