package io.karandash.cli.plugin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.registry.REGISTRY_URL
import io.karandash.registry.RegistryAuth
import kotlinx.io.files.Path

/** `plugin registry`: shows or sets the registry the plugin commands use (`plugins.registry-url` in the manifest). */
class PluginRegistryCommand : CliktCommand(name = "registry") {
    override fun help(context: Context) = "Show or set the plugin registry URL"

    override fun run() = Unit
}

/** `plugin registry show`: the effective registry URL and where it comes from. */
class PluginRegistryShowCommand(
    private val manifestPath: Path = PLUGIN_MANIFEST_PATH,
) : SuspendCliktCommand(name = "show") {
    override fun help(context: Context) = "Show the registry URL the plugin commands use"

    override suspend fun suspendRun() {
        val manifest = readPluginManifest(manifestPath) { echo("Warning: $it", err = true) }
        val url = manifest.registryUrl
        echo(if (url == null) "Registry: $REGISTRY_URL (default)" else "Registry: $url (from $manifestPath)")
        if (manifest.registryToken != null) echo("Auth: token set (${manifest.registryAuthHeader ?: RegistryAuth.DEFAULT_HEADER_NAME})")
    }
}

/** `plugin registry set <url>`: writes `plugins.registry-url` to the project manifest. */
class PluginRegistrySetCommand(
    private val manifestPath: Path = PLUGIN_MANIFEST_PATH,
) : SuspendCliktCommand(name = "set") {
    override fun help(context: Context) = "Set the registry URL in .karandash/manifest.yaml (plugins.registry-url)"

    private val url by argument(help = "HTTPS URL of the registry index (plugins.json)")

    override suspend fun suspendRun() {
        if (!url.startsWith("https://")) {
            echo("Error: Only HTTPS registry URLs are allowed: $url", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        val error = setRegistryUrlInManifest(url, manifestPath)
        if (error != null) {
            echo("Error: $error", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        echo("Registry: $url (written to $manifestPath)")
    }
}
