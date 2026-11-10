package io.karandash.cli.commands.wizard

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ProfileModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.config.pipeline.ConfigFormatRegistry

/**
 * Native setup wizard for interactive Karandash configuration.
 *
 * Extends [SetupWizardBase] for shared wizard logic; overrides auth info
 * and next-steps display with platform-specific token variable names.
 * Supports YAML, JSON, and KTS output formats.
 */
internal class SetupWizard(
    terminal: Terminal,
    formatRegistry: ConfigFormatRegistry
) : SetupWizardBase(terminal, formatRegistry) {
    private var providerModules: List<PlatformServiceModule> = emptyList()

    suspend fun run(
        outputPath: String,
        skipDetection: Boolean,
        format: String?,
        providers: List<PlatformServiceModule> = emptyList(),
        profiles: List<ProfileModule> = emptyList()
    ) {
        providerModules = providers
        try {
            showWelcome()

            val detection = if (!skipDetection) detectPlatform(providers) else null
            val platform = selectPlatform(detection, providers)
            val profileId = selectProfile(profiles)
            val selectedFormat = format ?: selectOutputFormat()

            showAuthInfo(platform, detection)

            val resolvedOutputPath = if (outputPath == formatRegistry.defaultRenderer().defaultOutputPath) {
                defaultOutputPath(selectedFormat)
            } else {
                outputPath
            }

            val context = WizardContext(
                detection = detection,
                platform = platform,
                profileId = profileId,
                outputPath = resolvedOutputPath
            )
            showSummary(context, selectedFormat)

            if (confirm("Generate configuration?")) {
                val tokenVar = platformTokenHint(platform)
                writeConfig(context, selectedFormat, tokenVar)
                terminal.println()
                terminal.println(TextColors.green("Done! Configuration written to $resolvedOutputPath"))
                showNextSteps(context)
            } else {
                terminal.println("Setup cancelled.")
            }
        } catch (ignored: Exception) {
            // boundary: interactive terminal prompts; any failure cancels the wizard
            ignored.rethrowCancellation()
            terminal.println()
            terminal.println("Setup cancelled.")
        }
    }

    override suspend fun platformTokenVar(platform: String): String = platformTokenHint(platform)

    override suspend fun showNextSteps(context: WizardContext) {
        terminal.println()
        terminal.println(TextColors.cyan("Next steps:"))
        terminal.println("  1. Review the generated configuration at ${context.outputPath}")
        val tokenVar = platformTokenHint(context.platform)
        terminal.println("  2. Set $tokenVar environment variable")
        terminal.println("  3. Run: karandash validate --platform ${context.platform} --mr-url <pr-url>")
        terminal.println("  4. Run: karandash doctor    to verify your setup")
        terminal.println()
    }

    private suspend fun platformTokenHint(platform: String): String {
        val provider = providerModules.find { it.id == platform }
        return provider?.authTokenHint() ?: "${platform.uppercase()}_TOKEN"
    }

    private fun defaultOutputPath(format: String): String =
        formatRegistry.getRendererOrNull(format)?.defaultOutputPath
            ?: formatRegistry.defaultRenderer().defaultOutputPath
}
