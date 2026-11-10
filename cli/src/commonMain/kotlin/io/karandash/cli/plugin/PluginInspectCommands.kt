package io.karandash.cli.plugin

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.crypto.KeyManager
import io.karandash.crypto.SignatureVerifier
import io.karandash.inspector.PluginInspector
import io.karandash.manifest.ManifestData
import io.karandash.platform.readBytes
import io.karandash.registry.InstalledPluginInfo
import io.karandash.registry.InstalledPluginLister
import io.karandash.registry.PluginRegistryEntry
import io.karandash.registry.RegistryClient
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private const val OPT_PLUGIN_DIR = "--plugin-dir"

/** The installed plugin [id] in the `--plugin-dir` / manifest / default directory; exits with CONFIG_ERROR when absent. */
private suspend fun SuspendCliktCommand.findInstalled(
    lister: InstalledPluginLister,
    manifest: ManifestData,
    pluginDir: String?,
    id: String,
): Pair<String, InstalledPluginInfo> {
    val dir = pluginDir ?: manifest.pluginDir ?: lister.defaultPluginDirPath()
    val plugin = if (lister.isDirectory(dir)) lister.listInstalledPlugins(dir).find { it.id == id } else null
    if (plugin == null) {
        lister.shutdown()
        echo("Error: Plugin not found: $id (in $dir)", err = true)
        throw ProgramResult(ExitCodes.CONFIG_ERROR)
    }
    return dir to plugin
}

/** The registry entry for [id]; exits with CONFIG_ERROR when the registry cannot be fetched. */
private suspend fun SuspendCliktCommand.registryEntry(
    client: RegistryClient,
    id: String,
): PluginRegistryEntry? {
    val registry = client.fetchRegistry().getOrElse { error ->
        echo("Error: Failed to fetch registry: ${error.message}", err = true)
        throw ProgramResult(ExitCodes.CONFIG_ERROR)
    }
    return registry.plugins.find { it.id == id }
}

/** `plugin info <id>`: metadata of an installed plugin, its file (`--source`) and host requirement (`--dependencies`). */
class PluginInfoCommand(
    private val lister: InstalledPluginLister,
    private val registryClient: (ManifestData) -> RegistryClient = { it.registryClient() },
) : SuspendCliktCommand(name = "info") {
    override fun help(context: Context) = "Show an installed plugin's ID, name, version and file"

    private val id by argument(help = "Installed plugin ID")
    private val pluginDir by option(OPT_PLUGIN_DIR, help = "Plugin directory")
    private val source by option("--source", help = "Print only the installed plugin file path").flag()
    private val dependencies by option(
        "--dependencies",
        help = "Look the plugin up in the registry and show the karandash version it requires",
    ).flag()

    override suspend fun suspendRun() {
        val manifest = readPluginManifest { echo("Warning: $it", err = true) }
        val (_, plugin) = findInstalled(lister, manifest, pluginDir, id)
        lister.shutdown()
        if (source) {
            echo(plugin.sourcePath ?: "unknown")
            return
        }
        echo("ID:      ${plugin.id}")
        echo("Name:    ${plugin.name}")
        echo("Version: ${plugin.version}")
        echo("File:    ${plugin.sourcePath ?: "unknown"}")
        if (!dependencies) return
        val entry = registryEntry(registryClient(manifest), plugin.id)
        if (entry == null) {
            echo("Requires: unknown (${plugin.id} is not in the registry)")
            return
        }
        echo("Requires: karandash >= ${entry.minHostVersion} (registry v${entry.version})")
        echo(entry.hostIncompatibility() ?: "Host: compatible")
    }
}

/** `plugin rebuild <id>`: re-downloads the registry build of an installed plugin and replaces the installed file. */
class PluginRebuildCommand(
    private val lister: InstalledPluginLister,
    private val registryClient: (ManifestData) -> RegistryClient = { it.registryClient() },
    private val trustedKeys: () -> List<ByteArray> = { KeyManager().allPublicKeys() },
) : SuspendCliktCommand(name = "rebuild") {
    override fun help(context: Context) =
        "Reinstall an installed plugin from the registry (fresh download, checksum-verified, old file backed up)"

    private val id by argument(help = "Installed plugin ID")
    private val pluginDir by option(OPT_PLUGIN_DIR, help = "Plugin directory")
    private val allowUnsigned by option(
        "--allow-unsigned",
        help = "Reinstall a JAR plugin without a signature (<file>.sig) from a trusted key",
    ).flag(default = false)

    override suspend fun suspendRun() {
        val manifest = readPluginManifest { echo("Warning: $it", err = true) }
        val (dir, plugin) = findInstalled(lister, manifest, pluginDir, id)
        val entry = registryEntry(registryClient(manifest), id)
        val problem = when {
            entry == null -> "Plugin '$id' is not in the registry"
            !entry.url.startsWith("https://") -> "Only HTTPS download URLs are allowed: ${entry.url}"
            else -> entry.hostIncompatibility()
        }
        if (problem != null || entry == null) {
            lister.shutdown()
            echo("Error: $problem", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        val keys = if (allowUnsigned) null else trustedKeys()
        val result = lister.downloadAndUpdate(entry.url, dir, plugin.sourcePath, id, entry.checksum, manifest.allowLocalhost, keys)
        lister.shutdown()
        result
            .onSuccess { (backedUp, _) ->
                backedUp?.let { echo("Backed up: $it") }
                echo("✓ Rebuilt $id v${entry.version} from the registry")
            }.onFailure { error ->
                echo("Error: ${error.message}", err = true)
                throw ProgramResult(ExitCodes.EXECUTION_ERROR)
            }
    }
}

/** `plugin load <file>`: checks that a plugin file is valid on this platform and reads its metadata, without installing it. */
class PluginLoadCommand(
    private val lister: InstalledPluginLister,
    private val inspector: PluginInspector,
) : SuspendCliktCommand(name = "load") {
    override fun help(context: Context) = "Check that a plugin file loads on this platform (no install)"

    private val path by argument(help = "Path to the plugin file (.jar, .so, .dylib, .dll, .js, .wasm)")

    override suspend fun suspendRun() {
        val error = lister.verifyPlugin(path)
        val metadata = if (error == null) inspector.readMetadata(path) else null
        lister.shutdown()
        if (metadata == null) {
            echo("Error: ${error ?: "No plugin metadata in $path"}", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        echo("✓ $path loads: ${metadata.id} v${metadata.version} (${metadata.name})")
    }
}

/**
 * `plugin verify <file> [<signature>]`: RSA-SHA256 signature check against `--key` or the trusted keys;
 * `--load` then runs the signed plugin to read the identity it declares ([PluginInspector.probeMetadata]).
 */
class PluginVerifyCommand(
    private val inspector: PluginInspector? = null,
    private val trustedKeys: () -> List<ByteArray> = { KeyManager().allPublicKeys() },
) : SuspendCliktCommand(name = "verify") {
    override fun help(context: Context) = "Verify a plugin file's RSA-SHA256 signature"

    private val path by argument(help = "Path to the plugin file")
    private val signature by argument(help = "Signature file (default: <file>.sig)").optional()
    private val keyPath by option(
        "--key",
        help = "Public key (X.509 DER) to verify with; default: the trusted keys in ~/.karandash/keys",
    )
    private val load by option(
        "--load",
        help = "After the signature checks out, execute the plugin's code (under the guest timeouts) to read the ID it declares",
    ).flag()

    override suspend fun suspendRun() {
        val sigPath = signature ?: "$path.sig"
        val missing = listOfNotNull(path, sigPath, keyPath).firstOrNull { !SystemFileSystem.exists(Path(it)) }
        if (missing != null) {
            echo("Error: File not found: $missing", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        val keys = keyPath?.let { listOf(Path(it).readBytes()) } ?: trustedKeys()
        if (keys.isEmpty()) {
            echo("Error: No trusted keys; pass --key <public-key.der>", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        if (keys.none { SignatureVerifier.verifySignature(Path(path), Path(sigPath), it) }) {
            echo("Error: Signature verification failed for $path", err = true)
            throw ProgramResult(ExitCodes.VALIDATION_FAILED)
        }
        echo("✓ Signature valid: $path")
        if (!load) return
        val declared = inspector?.probeMetadata(path)
        if (declared == null) {
            echo("Error: $path did not report plugin metadata when loaded", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        echo("✓ Loaded: ${declared.id} v${declared.version} (${declared.name})")
    }
}
