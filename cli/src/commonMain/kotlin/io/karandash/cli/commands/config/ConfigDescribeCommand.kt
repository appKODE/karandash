package io.karandash.cli.commands.config

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.ConfigFileOptions
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.cli.pipeline.EffectiveConfigDescriber
import io.karandash.cli.pipeline.loadOrReport

/**
 * Describes a config key or section, showing its purpose and expected structure; with `--effective`,
 * prints every effective config value with its source and the AI provider chain.
 *
 * Usage:
 * ```
 * karandash config describe rules
 * karandash config describe timeouts
 * karandash config describe --effective [--config path]
 * ```
 */
class ConfigDescribeCommand(
    private val bootstrap: PluginBootstrap? = null,
) : SuspendCliktCommand(name = "describe") {
    override fun help(context: Context) = "Describe a config key or section, or the effective config with --effective"

    private val key by argument(help = "Config key to describe (e.g., 'rules', 'policies', 'timeouts')").optional()
    private val effective by option("--effective", help = "Print every effective config value with its source").flag()
    private val configOptions by ConfigFileOptions()
    private val configPath get() = configOptions.path

    override suspend fun suspendRun() {
        if (effective) return describeEffective()
        val key = this.key
        val description = key?.let { DESCRIPTIONS[it] }
        if (key == null || description == null) {
            echo(if (key == null) "Specify a config key or --effective" else "Unknown config key: '$key'", err = true)
            echo("", err = true)
            echo("Available keys: ${DESCRIPTIONS.keys.sorted().joinToString(", ")}", err = true)
            throw ProgramResult(ExitCodes.VALIDATION_FAILED)
        }
        echo("$key:")
        echo("  $description")
    }

    private suspend fun describeEffective() {
        val shared = checkNotNull(bootstrap) { "config describe --effective needs the plugin bootstrap" }
        val logger = CliLogger(quiet = true)
        val stage = ConfigLoader.forCommand(configPath, shared, logger).loadOrReport { echo(it, err = true) }
            ?: throw ProgramResult(ExitCodes.CONFIG_ERROR)
        echo("source: ${stage.configFilePath ?: "zero-config defaults"}")
        EffectiveConfigDescriber.describe(stage.config, stage.configLayers).forEach { echo(it) }
    }

    companion object {
        internal val DESCRIPTIONS = mapOf(
            "rules" to "Rule configurations. Each key is a rule ID, value is rule-specific config " +
                "(enabled, severity, thresholds, patterns).",
            "policies" to "Conditional policy overrides. Array of objects with 'when' condition, " +
                "'modules', 'enabled', 'severity', and 'override' fields.",
            "timeouts" to "Timeout configuration: http, enrichment, pluginShutdown, cacheTtl, ruleExecution, scripts, dangerfile, global.",
            "http" to "HTTP client settings: retry (maxAttempts, baseDelay), pagination.maxPages, responseCache.maxEntries.",
            "limits" to "DoS guards, clamped to safe bounds: maxFindings, fetchTimeout, expressions, extends, regex, patterns " +
                "(a parent's fetchTimeout, extends and glob also apply to fetching its own parents).",
            "display" to "Display preferences: watcher.debounce (--watch), console.successMark/failureMark.",
            "report" to "Report preferences: language (BCP-47 tag, default en), " +
                "transparency.summary (off, collapsed, expanded; default collapsed), transparency.inline-why (default false), " +
                "transparency.flow-diagram (default false), walkthrough (off, collapsed; default off), " +
                "style (quiet, standard, verbose; default standard).",
            "security" to "Network security: blocked-hosts (extra hostnames every outbound URL check refuses).",
            "reporters" to "Reporter configurations. Each key is a format/output ID with format-specific options.",
            "platforms" to "Platform service configurations (gitlab, github, bitbucket, etc.) with auth and API settings.",
            "profile" to "Active profile reference (e.g., 'conventional-commits'). Applies a predefined rule configuration set.",
            "enrichment" to "Pre-validation enrichment configuration. Enables data providers that augment the MR context.",
            "ai" to "AI-powered analysis configuration: provider, model, analyzers, and prompt settings.",
            "cache" to "Cache configuration: state directory, response TTL, remote backend settings.",
            "patterns" to "User-defined named patterns for reuse in rule configurations (glob or regex).",
            "auto-approve" to "Auto-approve settings: enabled flag, maxSeverity threshold for auto-approval, merge after approving.",
            "extends" to "Config inheritance. Path or URL to a parent config that this config extends.",
            "plugins" to "External third-party plugin configurations. Each entry specifies a plugin source and config.",
        )
    }
}
