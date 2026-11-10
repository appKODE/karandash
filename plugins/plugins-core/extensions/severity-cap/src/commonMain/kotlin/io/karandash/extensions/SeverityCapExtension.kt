package io.karandash.extensions

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.PipelineExtension
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shadow mode (`severity-cap:`): lowers every finding more severe than the cap to the cap, so a new preset can run
 * in CI without blocking merges. `severity-cap: warning` at the top level caps every rule; `rules.<id>.severity-cap`
 * caps one rule and wins over the top-level value (`error` there takes the rule out of the global cap). No cap
 * configured (the default) leaves findings untouched. Runs after every other `afterValidation` hook, so AI findings
 * are capped too; the run's result and exit code follow the capped severities.
 */
class SeverityCapExtension : PipelineExtension {
    companion object {
        const val ID = "severity-cap"
        const val CONFIG_KEY = "severityCap"
        private const val RULES_KEY = "rules"
        private const val PRIORITY = 1000
    }

    private var logger: Logger = NoOpLogger

    override val id: String = ID
    override val name: String = "Severity Cap"
    override val version: String = "1.0.0"
    override val type: ModuleType = ModuleType.PIPELINE_EXTENSION
    override val description: String = "Shadow mode: caps finding severity globally or per rule (severity-cap)"
    override val defaultPriority: Int = PRIORITY

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        return true
    }

    override suspend fun afterValidation(
        findings: List<Finding>,
        mr: MergeRequest,
        config: JsonElement?,
    ): List<Finding> {
        val root = config as? JsonObject ?: return findings
        val global = capOf(root, "top level")
        val rules = root[RULES_KEY] as? JsonObject ?: JsonObject(emptyMap())
        val perRule = rules
            .mapNotNull { (ruleId, block) ->
                (block as? JsonObject)?.let { capOf(it, ruleId) }?.let { ruleId to it }
            }.toMap()
        if (global == null && perRule.isEmpty()) return findings
        return findings.map { finding ->
            val cap = ModuleIdNormalizer.resolve(finding.rule, perRule.keys)?.let(perRule::get) ?: global
            if (cap != null && finding.severity < cap) finding.copy(severity = cap) else finding
        }
    }

    /** The cap set in [block] (`severityCap`, or `severity-cap` before key normalization); unknown values are warned about and ignored. */
    private fun capOf(
        block: JsonObject,
        where: String,
    ): Severity? {
        val value = (block[CONFIG_KEY] ?: block[ID]) as? JsonPrimitive ?: return null
        val cap = Severity.entries.firstOrNull { it.name.equals(value.content, ignoreCase = true) }
        if (cap == null) logger.warn("severity-cap '${value.content}' ($where) is not error, warning or info; ignored")
        return cap
    }
}
