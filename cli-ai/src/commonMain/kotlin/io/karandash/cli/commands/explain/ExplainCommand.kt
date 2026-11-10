package io.karandash.cli.commands.explain

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.errors.MissingPluginException
import io.karandash.api.exception.KarandashException
import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.CliEnvVars
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.Config
import io.karandash.config.aiProviderId
import io.karandash.config.pipeline.ZeroConfigDefaults
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.writeText
import io.karandash.plugins.ai.providers.AiProvider
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/**
 * CLI command that explains the current configuration by analyzing it against module schemas.
 *
 * For each configured module, shows which fields are valid, which have type mismatches,
 * which are unknown (with typo suggestions), and which required fields are missing.
 * Also lists available-but-unconfigured modules.
 *
 * Usage:
 * ```
 * karandash explain                       # text output, auto-discover config
 * karandash explain --format markdown     # markdown output
 * karandash explain --format json         # machine-readable JSON
 * karandash explain --format doc          # user-friendly documentation
 * karandash explain --format doc --ai     # doc with AI-generated examples
 * karandash explain --config path/to/config.yaml
 * karandash explain --section rules       # only explain rules
 * ```
 *
 * @param bootstrap The plugin bootstrap used to access the module registry.
 * @param loadConfig Loads the config at a path (auto-discovered when `null`) through the full config loader
 *   (`extends:`, `{ file: }`, `env:`), injected by `cli` since `cli-ai` cannot depend on it.
 */
class ExplainCommand(
    private val bootstrap: PluginBootstrap,
    private val loadConfig: suspend (configPath: String?) -> ExplainConfigSource,
) : SuspendCliktCommand(
        name = "explain",
    ) {
    override fun help(context: Context) = "Explain configuration: show valid/invalid fields, defaults, and available modules"

    private val configPath by option(
        "--config",
        "-c",
        help = "Path to config file (default: auto-discover)",
        envvar = CliEnvVars.CONFIG,
    )

    private val format by option(
        "--format",
        "-f",
        help = "Output format"
    ).choice(ExplainRendererRegistry.supportedFormats().associateWith { it }).default("text")

    private val ai by option(
        "--ai",
        help = "Enhance doc output with AI-generated examples (requires API key)"
    ).flag(default = false)

    private val outputPath by option(
        "--output",
        "-o",
        help = "Write output to file instead of stdout"
    )

    override suspend fun suspendRun() {
        val registry = bootstrap.registry()
        val explainer = ConfigExplainer(registry)

        val configResult = loadConfigOrDefaults()
        val result = explainer.explain(configResult.config, configResult.path)

        val baseOutput = ExplainRendererRegistry.render(format, result)
        val output = if (format == "doc" && ai) enhanceDoc(baseOutput, result, configResult.config) else baseOutput

        val outPath = outputPath
        if (outPath != null) {
            Path(outPath).absolutePathOrSelf().writeText(output)
            echo("Config explanation written to $outPath")
        } else {
            echo(output)
        }
    }

    private suspend fun enhanceDoc(
        templateDoc: String,
        result: ExplainResult,
        config: Config,
    ): String {
        val provider = resolveAiProvider(config)
        if (provider == null) {
            echo(
                "Warning: --ai flag set but no API key found. " +
                    "Set an AI provider API key (e.g. ANTHROPIC_API_KEY, OPENAI_API_KEY). " +
                    "Returning template-only output.",
                err = true,
            )
            return templateDoc
        }

        return try {
            val enhancer = DocAiEnhancer(provider, warn = { echo(it, err = true) })
            enhancer.enhance(templateDoc, result)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            // boundary: LLM provider call; falls back to the template doc
            e.rethrowCancellation()
            echo("Warning: AI enhancement failed: ${e.message}. Returning template-only output.", err = true)
            templateDoc
        } finally {
            provider.close()
        }
    }

    private fun resolveAiProvider(config: Config): AiProvider? =
        pickAiProvider(
            bootstrap.registry().aiProviders(),
            config,
            warn = { echo("Warning: $it", err = true) },
            env = PlatformEnvironment::getEnv,
        ) as? AiProvider

    /**
     * The config through the injected loader; one that does not load is reported and replaced by zero-config defaults,
     * except a missing plugin under `plugins.on-missing: fail`, which fails the command as it fails `validate`.
     */
    private suspend fun loadConfigOrDefaults(): ExplainConfigSource =
        try {
            loadConfig(configPath).let { if (configPath != null) it.copy(path = configPath) else it }
        } catch (e: MissingPluginException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        } catch (e: KarandashException) {
            fallback(e)
        } catch (e: IOException) {
            fallback(e)
        } catch (e: IllegalArgumentException) {
            fallback(e)
        }

    private suspend fun fallback(e: Exception): ExplainConfigSource {
        echo("Warning: Failed to parse config: ${e.message}", err = true)
        return ExplainConfigSource(path = configPath, config = ZeroConfigDefaults.defaultConfig(bootstrap.registry()))
    }
}

/**
 * The provider `ai.provider` names (warned through [warn] when none is registered under that id), else the first
 * whose declared API key or base URL variable is set in [env].
 */
internal fun pickAiProvider(
    providers: List<AiProviderModule>,
    config: Config,
    warn: (String) -> Unit = {},
    env: (String) -> String?,
): AiProviderModule? {
    val configured = config.aiProviderId() ?: return providers.firstOrNull { module ->
        listOfNotNull(module.apiKeyEnvVar, module.baseUrlEnvVar).any { env(it) != null }
    }
    val available = providers.joinToString { it.providerId }
    return providers.firstOrNull { it.providerId.equals(configured, ignoreCase = true) }.also {
        if (it == null) warn("ai.provider '$configured' is not a registered AI provider (available: $available)")
    }
}

/** The config `explain` describes and the path it was read from (`null` for zero-config defaults). */
@Serializable
data class ExplainConfigSource(
    val path: String?,
    val config: Config,
)
