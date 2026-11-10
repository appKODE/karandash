package io.karandash.plugins.ai

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiProviderException
import io.karandash.api.facet.PluginCommandContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.module.CliCommandModule
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.AiEnvVars
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AnthropicProvider
import io.karandash.plugins.ai.providers.CustomAiProvider
import io.karandash.plugins.ai.providers.GeminiProvider
import io.karandash.plugins.ai.providers.OllamaProvider
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.karandash.security.TextRedactor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * CLI command module for AI-powered MR analysis.
 *
 * Contributes `karandash ai fix`, `karandash ai reply` and `karandash ai describe` commands.
 *
 * the plugin config is the config's `ai` block ([configKey]); `ai.provider` and `ai.baseUrl` pick the
 * provider and endpoint. Without `ai.provider`, the first of `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`,
 * `GEMINI_API_KEY`, `OLLAMA_HOST` that is set picks it. With neither, [initialize] returns `false`.
 */
class AiCliCommandModule(
    private val providerFactory: suspend (PluginCommandContext) -> AiProvider? = ::createProvider,
) : CliCommandModule {
    override val id: String = "io.karandash.cli.ai"
    override val name: String = "AI CLI Commands"
    override val version: String = "1.7.0"
    override val type: ModuleType get() = ModuleType.CLI_COMMAND
    override val configKey: String get() = CONFIG_KEY

    override suspend fun initialize(context: PluginContext): Boolean =
        configValue(context, PROVIDER_KEY) != null || ENV_PROVIDERS.any { (envVar, _) -> context.env(envVar) != null }

    override suspend fun cliCommands(): List<PluginCommandSpec> =
        listOf(
            PluginCommandSpec(
                name = "ai",
                help = "AI-powered MR analysis",
                subcommands = listOf(
                    PluginCommandSpec(
                        name = "fix",
                        help = "Suggest code fixes for validation failures",
                        run = ::handleFix,
                    ),
                    PluginCommandSpec(
                        name = "reply",
                        help = "Generate an AI response to a code review comment",
                        run = ::handleReply,
                    ),
                    PluginCommandSpec(
                        name = "describe",
                        help = "Generate a suggested MR description; the mr-summary analyzer gives the same during validate",
                        run = ::handleDescribe,
                    ),
                ),
            )
        )

    private suspend fun handleFix(ctx: PluginCommandContext): Int {
        val ruleId = parseFlag(ctx.rawArgs, "--rule")
        val message = parseFlag(ctx.rawArgs, "--message")

        if (ruleId == null || message == null) {
            ctx.echo("Usage: karandash ai fix --rule <rule-id> --message <finding-message>")
            return 2
        }

        return complete(
            ctx,
            prompt = "A karandash validation rule '$ruleId' produced this finding: '$message'. " +
                "Suggest a specific, actionable fix. Provide concrete steps the developer should take.",
            systemPrompt = "You are a code review assistant helping developers fix MR validation failures. " +
                "Be specific and actionable.",
            maxTokens = 1000,
        )
    }

    private suspend fun handleReply(ctx: PluginCommandContext): Int {
        val comment = parseFlag(ctx.rawArgs, "--comment")
        if (comment == null) {
            ctx.echo("Usage: karandash ai reply --comment <review-comment> [--context <additional-context>]")
            return 2
        }

        val context = parseFlag(ctx.rawArgs, "--context") ?: ""
        val prompt = buildString {
            appendLine("A reviewer commented: '$comment'")
            if (context.isNotEmpty()) {
                appendLine()
                appendLine("Context: $context")
            }
            appendLine()
            appendLine("Provide a professional, helpful response.")
        }
        return complete(
            ctx,
            prompt = prompt,
            systemPrompt = "You are an expert developer responding to code review comments. " +
                "Provide clear, helpful, technically accurate responses. " +
                "Be concise and professional.",
            maxTokens = 1000,
        )
    }

    private suspend fun handleDescribe(ctx: PluginCommandContext): Int {
        val title = parseFlag(ctx.rawArgs, "--title") ?: ""
        val diff = parseFlag(ctx.rawArgs, "--diff") ?: ""

        if (title.isEmpty() && diff.isEmpty()) {
            ctx.echo("Usage: karandash ai describe --title <mr-title> [--diff <diff-summary>]")
            return 2
        }

        val prompt = buildString {
            appendLine("Generate a professional MR description for the following:")
            appendLine("Title: $title")
            if (diff.isNotEmpty()) {
                appendLine("Diff summary: $diff")
            }
        }
        return complete(ctx, prompt = prompt, systemPrompt = DESCRIBE_SYSTEM_PROMPT, maxTokens = 1500)
    }

    /**
     * secrets are redacted from the prompt and the provider is closed on every path.
     * model and temperature come from `--model`/`--temperature`, then the plugin config (`model`,
     * `temperature`, top-level or under `ai`), then the provider's cheap default.
     */
    private suspend fun complete(
        ctx: PluginCommandContext,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
    ): Int {
        val rawTemperature = parseFlag(ctx.rawArgs, "--temperature") ?: configValue(ctx.pluginContext, "temperature")
        val temperature = rawTemperature?.let { raw ->
            raw.toDoubleOrNull() ?: run {
                ctx.echoError("Error: temperature must be a number, got '$raw'")
                return EXIT_CONFIG_ERROR
            }
        } ?: DEFAULT_TEMPERATURE
        val provider = providerFactory(ctx)
        if (provider == null) {
            ctx.echoError(NO_API_KEY_MESSAGE)
            return 1
        }
        val configuredModel = parseFlag(ctx.rawArgs, "--model") ?: configValue(ctx.pluginContext, "model")
        return try {
            val response = provider.complete(
                prompt = TextRedactor.redact(prompt),
                systemPrompt = systemPrompt,
                model = configuredModel ?: AiDefaultModels.cheapFor(provider.providerId),
                maxTokens = maxTokens,
                temperature = temperature,
            )
            ctx.echo(response.content)
            0
        } catch (e: AiProviderException) {
            ctx.echoError(providerErrorMessage(e))
            EXIT_PROVIDER_ERROR
        } finally {
            provider.close()
        }
    }

    companion object {
        private val DESCRIBE_SYSTEM_PROMPT =
            """
            You are an expert technical writer. Based on the merge request diff and commits, generate a clear, comprehensive MR description.

            Respond with ONLY valid JSON:
            {
              "suggestedTitle": "clear, descriptive title",
              "suggestedDescription": "full markdown description",
              "changesSummary": ["bullet 1: change description", "bullet 2"]
            }

            Format the suggestedDescription as markdown with these sections:
            ## What
            What was changed

            ## Why
            Motivation and context

            ## How to test
            Testing steps

            ## Risks
            Any potential risks or things reviewers should focus on
            """.trimIndent()

        private const val NO_API_KEY_MESSAGE =
            "No AI provider found. Set `ai.provider` (openai, anthropic, gemini, ollama, custom with `ai.baseUrl`) " +
                "or one of OPENAI_API_KEY, ANTHROPIC_API_KEY, GEMINI_API_KEY, OLLAMA_HOST to use AI features."

        private const val DEFAULT_TEMPERATURE = 0.3

        // The CLI's ExitCodes (cli-ai), which a plugin tier module cannot depend on.
        private const val EXIT_CONFIG_ERROR = 2
        private const val EXIT_PROVIDER_ERROR = 3

        private fun providerErrorMessage(e: Exception) = "AI provider error: ${e.message}"

        internal const val CONFIG_KEY = "ai"
        private const val PROVIDER_KEY = "provider"
        private const val OPENAI = "openai"
        private const val ANTHROPIC = "anthropic"
        private const val GEMINI = "gemini"
        private const val OLLAMA = "ollama"
        private const val CUSTOM = "custom"

        /** Env var that selects a provider when `ai.provider` is not set, in detection order. */
        private val ENV_PROVIDERS = listOf(
            AiEnvVars.OPENAI_API_KEY to OPENAI,
            AiEnvVars.ANTHROPIC_API_KEY to ANTHROPIC,
            AiEnvVars.GEMINI_API_KEY to GEMINI,
            AiEnvVars.OLLAMA_HOST to OLLAMA,
        )

        private fun configValue(
            context: PluginContext,
            key: String,
        ): String? {
            val config = context.pluginConfig as? JsonObject ?: return null
            val value = config[key] ?: (config[CONFIG_KEY] as? JsonObject)?.get(key)
            return (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        /**
         * the provider named by `ai.provider` (else the first one with its env var set), pointed at
         * `ai.baseUrl` when given and initialized with the `ai:` block; `null` for an unknown provider, `custom` without
         * a base URL, a provider that does not initialize (no API key), or nothing set.
         */
        internal suspend fun createProvider(ctx: PluginCommandContext): AiProvider? {
            val context = ctx.pluginContext
            val name = configValue(context, PROVIDER_KEY)?.lowercase()
                ?: ENV_PROVIDERS.firstOrNull { (envVar, _) -> context.env(envVar) != null }?.second
            val baseUrl = configValue(context, "baseUrl")
            val provider = when (name) {
                OPENAI -> OpenAiProvider(context.env(AiEnvVars.OPENAI_API_KEY))

                ANTHROPIC -> AnthropicProvider(context.env(AiEnvVars.ANTHROPIC_API_KEY).orEmpty())

                GEMINI -> GeminiProvider(context.env(AiEnvVars.GEMINI_API_KEY).orEmpty())

                OLLAMA -> context.env(AiEnvVars.OLLAMA_HOST)?.let { OllamaProvider(baseUrl = OllamaProvider.baseUrlForHost(it)) }
                    ?: OllamaProvider()

                CUSTOM -> baseUrl?.let { CustomAiProvider(apiKey = context.env(AiEnvVars.CUSTOM_AI_API_KEY), baseUrl = it) }

                else -> null
            }
            provider?.configure(apiKey = null, baseUrl = baseUrl)
            // the ai block as the provider's config, so `ai.ollama.allowHosts` applies here too
            return provider?.takeIf { it.initialize(context) }
        }

        internal fun parseFlag(
            args: List<String>,
            flag: String
        ): String? {
            val index = args.indexOf(flag)
            if (index < 0 || index + 1 >= args.size) return null
            return args[index + 1]
        }
    }
}
