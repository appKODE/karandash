package io.karandash.cli.commands.wizard

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.prompt
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ProfileModule
import io.karandash.config.keys.ModuleIds
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.detect.DetectionResult
import io.karandash.detect.PlatformDetector
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.createDirectories
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Base class for setup wizards, containing shared interactive wizard logic.
 *
 * Platform-specific wizards extend this class and override methods that
 * differ per platform (auth info display, next steps).
 */
private const val PROFILE_MINIMAL = ModuleIds.MINIMAL_PROFILE

open class SetupWizardBase(
    protected val terminal: Terminal,
    protected val formatRegistry: ConfigFormatRegistry
) {
    fun showWelcome() {
        terminal.println()
        terminal.println(TextColors.blue("+===========================================+"))
        terminal.println(TextColors.blue("|   Karandash Setup Wizard                  |"))
        terminal.println(TextColors.blue("+===========================================+"))
        terminal.println()
        terminal.println("Let's set up Karandash for your project!")
        terminal.println()
    }

    suspend fun detectPlatform(providers: List<PlatformServiceModule> = emptyList()): DetectionResult? {
        terminal.println(TextColors.cyan("-> Detecting platform..."))
        val detector = PlatformDetector(providers)
        val detection = detector.detect()
        if (detection != null) {
            terminal.println("  Detected: ${detection.platform} (confidence: ${detection.confidence})")
        } else {
            terminal.println("  Could not auto-detect platform")
        }
        return detection
    }

    fun selectPlatform(
        detection: DetectionResult?,
        registeredProviders: List<PlatformServiceModule> = emptyList()
    ): String {
        val platforms = registeredProviders.sortedBy { it.defaultPriority }.map { it.id }
        val fallback = platforms.firstOrNull() ?: "github"
        if (platforms.isEmpty()) return fallback
        val defaultPlatform = detection
            ?.platform
            ?.takeIf { it in platforms }
            ?: fallback
        val defaultIndex = platforms.indexOf(defaultPlatform).coerceAtLeast(0)

        terminal.println()
        terminal.println(TextColors.cyan("-> Select platform:"))
        platforms.forEachIndexed { index, platform ->
            val marker = if (platform == defaultPlatform) "*" else " "
            terminal.println("  $marker ${index + 1}. $platform")
        }

        val input = terminal.prompt("Choice [1-${platforms.size}]", default = (defaultIndex + 1).toString())
        val choice = input?.toIntOrNull()?.minus(1) ?: defaultIndex
        return platforms.getOrElse(choice.coerceIn(0, platforms.size - 1)) { defaultPlatform }
    }

    /**
     * Interactively selects a profile from the registered profiles.
     *
     * @param profiles Available profiles from the module registry.
     * @return The selected [ProfileModule.profileId].
     */
    fun selectProfile(profiles: List<ProfileModule>): String {
        if (profiles.isEmpty()) return PROFILE_MINIMAL

        terminal.println()
        terminal.println(TextColors.cyan("-> Select profile:"))
        profiles.forEachIndexed { index, profile ->
            val marker = if (profile.profileId == PROFILE_MINIMAL) "*" else " "
            terminal.println("  $marker ${index + 1}. ${profile.profileId} — ${profile.summary}")
        }

        val defaultIndex = profiles.indexOfFirst { it.profileId == PROFILE_MINIMAL }.coerceAtLeast(0)
        val input = terminal.prompt("Choice [1-${profiles.size}]", default = (defaultIndex + 1).toString())
        val choice = input?.toIntOrNull()?.minus(1) ?: defaultIndex
        return profiles.getOrElse(choice.coerceIn(0, profiles.size - 1)) { profiles[defaultIndex] }.profileId
    }

    /**
     * Interactively selects the output config format: yaml, json, or kts.
     *
     * @return One of `FORMAT_YAML`, `FORMAT_JSON`, or `"kts"`.
     */
    fun selectOutputFormat(): String {
        val renderers = formatRegistry.allRenderers()
        val formats = renderers.map { it.formatId }
        val defaultFormat = formats.firstOrNull() ?: "yaml"

        terminal.println()
        terminal.println(TextColors.cyan("-> Select config format:"))
        renderers.forEachIndexed { index, renderer ->
            val marker = if (renderer.formatId == defaultFormat) "*" else " "
            terminal.println("  $marker ${index + 1}. ${renderer.formatId.uppercase()} (${renderer.defaultOutputPath})")
        }

        val input = terminal.prompt("Choice [1-${formats.size}]", default = "1")
        val choice = input?.toIntOrNull()?.minus(1) ?: 0
        return formats.getOrElse(choice.coerceIn(0, formats.size - 1)) { defaultFormat }
    }

    fun confirm(message: String): Boolean {
        val response = terminal.prompt("$message (y/n)", default = "y")
        return response?.lowercase() in listOf("y", "yes", "")
    }

    open suspend fun showAuthInfo(
        platform: String,
        detection: DetectionResult?
    ) {
        terminal.println()
        terminal.println(TextColors.cyan("-> Authentication:"))
        if (detection?.token != null) {
            terminal.println("  Token detected for $platform")
        } else {
            val tokenVar = platformTokenVar(platform)
            terminal.println("  No token detected. Set $tokenVar environment variable")
        }
    }

    /**
     * Returns the environment variable name for the platform token.
     *
     * Override in subclasses to resolve the token hint from a provider registry.
     */
    open suspend fun platformTokenVar(platform: String): String = "${platform.uppercase()}_TOKEN"

    fun showSummary(
        context: WizardContext,
        format: String
    ) {
        terminal.println()
        terminal.println(TextColors.green("=== Configuration Summary ==="))
        terminal.println("  Platform: ${context.platform}")
        terminal.println("  Profile:  ${context.profileId}")
        terminal.println("  Format:   $format")
        terminal.println("  Output:   ${context.outputPath}")
        terminal.println()
    }

    /**
     * Writes a profile-based config to [context.outputPath] in the given [format].
     *
     * Generates a minimal working config with `profile:` and `providers:` blocks.
     *
     * @param tokenVar Environment variable name for the platform token (e.g. `"GITHUB_TOKEN"`).
     */
    fun writeConfig(
        context: WizardContext,
        format: String,
        tokenVar: String
    ) {
        val configContent = formatRegistry
            .getRenderer(format)
            .renderWizardConfig(context.profileId, context.platform, tokenVar)
        val file = Path(context.outputPath).absolutePathOrSelf()
        val parent = file.safeParent
        if (parent != null) {
            createDirectories(parent)
        }
        file.writeText(configContent)
    }

    open suspend fun showNextSteps(context: WizardContext) {
        terminal.println()
        terminal.println(TextColors.cyan("Next steps:"))
        terminal.println("  1. Review the generated configuration at ${context.outputPath}")
        terminal.println("  2. Set ${context.platform.uppercase()}_TOKEN environment variable")
        terminal.println("  3. Run: karandash validate --platform ${context.platform} --mr-url <pr-url>")
        terminal.println("  4. Run: karandash doctor    to verify your setup")
        terminal.println()
    }
}
