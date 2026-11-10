package io.karandash.plugins.ai.hooks

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiService
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiHookAction
import io.karandash.plugins.ai.core.AiHookConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Executes config-driven AI hooks at defined pipeline points.
 *
 * Hook points: `post-fetch`, `post-validation`, `pre-report`, `per-finding`.
 *
 * Template variables in prompts are replaced before sending to the AI provider:
 * - `{{findings_json}}` - JSON-serialized list of findings
 * - `{{mr_description}}` - MR description text
 * - `{{mr_title}}` - MR title
 * - `{{changed_files}}` - comma-separated list of changed file paths
 * - `{{finding_message}}` - individual finding message (per-finding hooks only)
 * - `{{finding_rule}}` - individual finding rule ID (per-finding hooks only)
 *
 * @param aiService The platform AI service for sending completions.
 * @param hooks Hook configurations keyed by hook point name.
 * @param defaultModel Default model to use when hook config doesn't specify one.
 */
class AiHookExecutor(
    private val aiService: AiService,
    private val hooks: Map<String, AiHookConfig>,
    private val defaultModel: String = AiDefaultModels.GENERIC,
) {
    private val json = KarandashJson.Pretty

    /**
     * Execute all hooks matching the given [hookPoint].
     *
     * @param hookPoint The pipeline point (e.g. "post-validation", "pre-report").
     * @param mr The merge request being validated.
     * @param findings Current findings (may be empty for early hook points).
     * @param log Logging callback for diagnostic output.
     * @return [AiHookResult] with any new findings or comment text produced by hooks.
     */
    suspend fun execute(
        hookPoint: String,
        mr: MergeRequest,
        findings: List<Finding> = emptyList(),
        log: (String) -> Unit = {},
    ): AiHookResult {
        val hookConfig = hooks[hookPoint] ?: return AiHookResult.EMPTY
        val reply = complete(hookConfig, buildTemplateVars(mr, findings))
        return toResult(hookConfig.action, hookPoint, hookPoint, reply, log)
    }

    /**
     * Execute per-finding hooks: runs the hook once for each finding.
     *
     * @return Aggregated [AiHookResult] across all findings.
     */
    suspend fun executePerFinding(
        mr: MergeRequest,
        findings: List<Finding>,
        log: (String) -> Unit = {},
    ): AiHookResult {
        val hookConfig = hooks[PER_FINDING] ?: return AiHookResult.EMPTY
        val shared = buildTemplateVars(mr, findings)
        val results = findings.map { finding ->
            val vars = shared + mapOf("finding_message" to finding.message, "finding_rule" to finding.rule)
            val reply = complete(hookConfig, vars)
            toResult(hookConfig.action, PER_FINDING, perFindingKey(finding), reply, log)
        }
        return AiHookResult.merge(results)
    }

    private suspend fun complete(
        hookConfig: AiHookConfig,
        vars: Map<String, String>,
    ): String {
        val request = AiCompletionRequest(
            prompt = applyTemplate(hookConfig.prompt, vars),
            model = hookConfig.model ?: defaultModel,
        )
        return aiService.complete(request, hookConfig.provider).content
    }

    private fun toResult(
        action: AiHookAction,
        hookPoint: String,
        metadataKey: String,
        reply: String,
        log: (String) -> Unit,
    ): AiHookResult =
        when (action) {
            AiHookAction.LOG -> {
                log("[ai-hook:$hookPoint] $reply")
                AiHookResult.EMPTY
            }

            AiHookAction.COMMENT -> {
                AiHookResult(comments = listOf(reply))
            }

            AiHookAction.METADATA -> {
                AiHookResult(metadata = mapOf(metadataKey to reply))
            }

            AiHookAction.FINDING -> {
                AiHookResult(findings = listOf(Finding(Severity.INFO, reply, "ai-hook-$hookPoint")))
            }
        }

    private fun buildTemplateVars(
        mr: MergeRequest,
        findings: List<Finding>
    ): Map<String, String> =
        mapOf(
            "findings_json" to json.encodeToString(findings),
            "mr_description" to (mr.description ?: ""),
            "mr_title" to mr.title,
            "changed_files" to mr.changes.joinToString(", ") { it.path },
        )

    /** Replaces each `{{name}}` in one pass, so `{{...}}` inside substituted MR or finding text stays literal. */
    private fun applyTemplate(
        template: String,
        vars: Map<String, String>
    ): String =
        buildString {
            var at = 0
            while (at < template.length) {
                val open = template.indexOf("{{", at)
                val close = if (open < 0) -1 else template.indexOf("}}", open + 2)
                if (close < 0) {
                    append(template, at, template.length)
                    break
                }
                val value = vars[template.substring(open + 2, close)]
                append(template, at, open)
                // Unknown key: keep one brace and rescan, so `{{{key}}}` still finds `{{key}}`.
                append(value ?: "{")
                at = if (value == null) open + 1 else close + 2
            }
        }

    companion object {
        /** Hook point of the hook that runs once per finding; its metadata keys read `per-finding:<rule>`. */
        const val PER_FINDING = "per-finding"

        /** Metadata key of a `per-finding` reply: the finding's identity, so two findings of one rule keep their own reply. */
        fun perFindingKey(finding: Finding): String =
            "$PER_FINDING:${finding.rule}:${finding.fingerprint ?: "${finding.location}|${finding.message}"}"

        /** Prefix of the finding metadata keys a `metadata` hook reply is stored under. */
        const val HOOK_METADATA_PREFIX = "aiHook."
    }
}

/**
 * Result from executing AI hooks.
 *
 * Aggregates findings, comments, and metadata produced by hook actions.
 */
@Serializable
data class AiHookResult(
    val findings: List<Finding> = emptyList(),
    val comments: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {
        val EMPTY = AiHookResult()

        fun merge(results: List<AiHookResult>): AiHookResult =
            AiHookResult(
                findings = results.flatMap { it.findings },
                comments = results.flatMap { it.comments },
                metadata = results.fold(emptyMap()) { acc, r -> acc + r.metadata },
            )
    }
}
