package io.karandash.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.bootstrap.currentPlatformKey
import io.karandash.cache.CacheManager
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.plugin.PLUGIN_MANIFEST_PATH
import io.karandash.cli.plugin.pinPluginInManifest
import io.karandash.cli.plugin.readPluginManifest
import io.karandash.cli.plugin.registryClient
import io.karandash.crypto.KeyManager
import io.karandash.crypto.SignatureVerifier
import io.karandash.download.ArtifactEntry
import io.karandash.download.PlatformArtifactResolver
import io.karandash.download.ResolvedArtifact
import io.karandash.inspector.PluginInspector
import io.karandash.loader.DownloadCache
import io.karandash.manifest.ManifestData
import io.karandash.registry.InstalledPluginLister
import io.karandash.registry.PluginRegistryEntry
import io.karandash.registry.PluginTemplateGenerator
import io.karandash.registry.RegistryClient
import io.karandash.registry.TEMPLATE_TYPE_LABELS
import io.karandash.registry.TemplateConfig
import kotlinx.io.files.Path

/**
 * Top-level command for plugin management (multiplatform).
 * Subcommands are wired via expect/actual pluginCommand() factory.
 */
private const val OPT_PLUGIN_DIR = "--plugin-dir"

/** `.karandash/manifest.yaml` plugin settings; an unreadable manifest is reported on stderr. */
private suspend fun CliktCommand.readManifestWarning(path: Path = PLUGIN_MANIFEST_PATH): ManifestData =
    readPluginManifest(path) { echo("Warning: $it", err = true) }

class PluginCommand : CliktCommand(name = "plugin") {
    override fun help(context: Context) = "Manage karandash plugins"

    override fun run() = Unit
}

/**
 * Lists all installed plugins (multiplatform); `--probe` also runs each one to show the identity it declares.
 */
class PluginListCommand(
    private val lister: InstalledPluginLister,
    private val inspector: PluginInspector? = null,
    private val manifestPath: Path = PLUGIN_MANIFEST_PATH,
) : SuspendCliktCommand(name = "list") {
    override fun help(context: Context) = "List installed plugins"

    private val pluginDir by option(
        OPT_PLUGIN_DIR,
        help = "Plugin directory path"
    )
    private val probe by option(
        "--probe",
        help = "Execute each installed plugin's code (under the guest timeouts) to show the ID and version it declares",
    ).flag()

    override suspend fun suspendRun() {
        val manifest = readManifestWarning(manifestPath)
        val dirPath = pluginDir ?: manifest.pluginDir ?: lister.defaultPluginDirPath()
        manifest.pluginDeclarations.takeIf { it.isNotEmpty() }?.let { declared ->
            echo("Declared in manifest:")
            declared.forEach { echo("  ${it.id} ${it.version?.let { v -> "v$v" } ?: "(latest)"}") }
        }

        if (!lister.isDirectory(dirPath)) {
            echo("No plugins installed ($dirPath does not exist)")
            return
        }

        val plugins = lister.listInstalledPlugins(dirPath)
        if (plugins.isEmpty()) {
            echo("No plugins installed in $dirPath")
            return
        }

        echo("Installed plugins ($dirPath):")
        for (plugin in plugins) {
            echo("  ${plugin.id} v${plugin.version} - ${plugin.name}")
            plugin.sourcePath?.let { path ->
                val fileName = path.substringAfterLast('/')
                echo("    JAR: $fileName")
                if (probe) {
                    val declared = inspector?.probeMetadata(path)
                    echo("    Declares: ${declared?.let { "${it.id} v${it.version}" } ?: "nothing (did not load)"}")
                }
            }
        }

        lister.shutdown()
    }
}

/**
 * Installs a plugin from a local file or plugin registry (multiplatform).
 */
class PluginInstallCommand(
    private val lister: InstalledPluginLister,
    private val registryClient: (ManifestData) -> RegistryClient = { it.registryClient() },
    private val manifestPath: Path = PLUGIN_MANIFEST_PATH,
    private val trustedKeys: () -> List<ByteArray> = { KeyManager().allPublicKeys() },
) : SuspendCliktCommand(name = "install") {
    override fun help(context: Context) = "Install a plugin from a local file or registry entry"

    private val path by argument(help = "Path to plugin file").optional()
    private val registryId by option("--registry", help = "Plugin ID from the registry")
    private val pluginDir by option(OPT_PLUGIN_DIR, help = "Plugin directory")
    private val pin by option(
        "--pin",
        help = "Resolve and record the installed version back to manifest.yaml"
    ).flag(default = false)
    private val allowUnsigned by option(
        "--allow-unsigned",
        help = "Install a JAR plugin without a signature (<file>.sig) from a trusted key"
    ).flag(default = false)

    override suspend fun suspendRun() {
        val manifest = readManifestWarning(manifestPath)
        val targetDir = pluginDir ?: manifest.pluginDir ?: lister.defaultPluginDirPath()

        if ((path == null && registryId == null) || (path != null && registryId != null)) {
            echo("Error: Specify exactly one source: local path argument OR --registry <plugin-id>", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        if (registryId != null) {
            installFromRegistry(
                manifest,
                targetDir,
                requireNotNull(registryId) { "Registry plugin ID must be set when installing from registry" }
            )
            lister.shutdown()
            return
        }

        val sourcePath = requireNotNull(path) { "Plugin file path must be set when installing from a local file" }
        val verifyError = lister.verifyPlugin(sourcePath)
        if (verifyError != null) {
            echo("Error: $verifyError", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        requireLocalSignature(sourcePath)

        lister
            .installPlugin(sourcePath, targetDir)
            .onSuccess { plugin ->
                echo("✓ Installed ${plugin.id} v${plugin.version}")
            }.onFailure { error ->
                echo("Error: ${error.message}", err = true)
                throw ProgramResult(ExitCodes.VALIDATION_FAILED)
            }

        lister.shutdown()
    }

    /** `--allow-unsigned` on a JAR: the loader then runs it unverified (it only checks JARs once a trusted key exists). */
    private fun warnUnsignedJar(location: String) {
        if (!allowUnsigned || !location.substringBefore('?').lowercase().endsWith(".jar")) return
        echo(
            "Warning: $location is installed without a signature check; karandash loads it unverified " +
                "while ~/.karandash/keys holds no trusted key",
            err = true
        )
    }

    private suspend fun requireLocalSignature(sourcePath: String) {
        warnUnsignedJar(sourcePath)
        if (allowUnsigned || !sourcePath.lowercase().endsWith(".jar")) return
        SignatureVerifier.verifyJar(Path(sourcePath), trustedKeys()).onFailure { error ->
            echo(
                "Error: $sourcePath is not signed by a trusted key (${error.message}); " +
                    "pass --allow-unsigned to install it anyway",
                err = true
            )
            throw ProgramResult(ExitCodes.VALIDATION_FAILED)
        }
    }

    private suspend fun installFromRegistry(
        manifest: ManifestData,
        targetDir: String,
        pluginId: String
    ) {
        echo("Fetching plugin registry...")
        val client = registryClient(manifest)
        val registry = client.fetchRegistry().getOrElse { error ->
            echo("Error: Failed to fetch registry: ${error.message}", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        val entry = registry.plugins.find { it.id == pluginId }
        if (entry == null) {
            echo("Error: Plugin '$pluginId' not found in registry", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        entry.hostIncompatibility()?.let { reason ->
            echo("Error: $reason. Upgrade karandash first.", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        val artifact = selectArtifact(entry)
            ?: run {
                echo(
                    "Error: No compatible artifact found for '${entry.id}' on platform ${currentPlatformKey()}",
                    err = true
                )
                throw ProgramResult(ExitCodes.CONFIG_ERROR)
            }

        if (!artifact.url.startsWith("https://")) {
            echo("Error: Only HTTPS download URLs are allowed: ${artifact.url}", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        warnUnsignedJar(artifact.url)
        val result = lister.downloadAndUpdate(
            downloadUrl = artifact.url,
            pluginDirPath = targetDir,
            oldSourcePath = null,
            pluginId = entry.id,
            expectedChecksum = artifact.checksum,
            allowLocalhost = manifest.allowLocalhost,
            signatureKeys = if (allowUnsigned) null else trustedKeys(),
        )

        result
            .onSuccess {
                echo("✓ Installed ${entry.id} v${entry.version}")
                if (pin) {
                    val pinError = pinPluginInManifest(entry.id, entry.version, manifestPath)
                    if (pinError == null) {
                        echo("Pinned: ${entry.id} → ${entry.version} in $manifestPath")
                    } else {
                        echo("Warning: $pinError", err = true)
                    }
                }
            }.onFailure { error ->
                echo("Error: ${error.message}", err = true)
                throw ProgramResult(ExitCodes.VALIDATION_FAILED)
            }
    }

    private fun selectArtifact(entry: PluginRegistryEntry): ResolvedArtifact? {
        val supportedFormats = lister.supportedFormats()
        val preferredPlatform = currentPlatformKey()
        val artifacts = entry.artifacts

        val entries = artifacts?.mapValues { (_, it) -> ArtifactEntry(it.url, it.checksum, it.format) }
        // Per-platform `artifacts` first; the top-level `url` is the last resort below, matched by its real extension.
        PlatformArtifactResolver(preferredPlatform)
            .resolve(entries)
            ?.takeIf { it.format in supportedFormats }
            ?.let { return it }

        if (artifacts != null) {
            artifacts[preferredPlatform]
                ?.takeIf { it.format in supportedFormats }
                ?.let { return ResolvedArtifact(it.url, it.checksum, it.format) }

            if (preferredPlatform != "jvm") {
                artifacts["jvm"]
                    ?.takeIf { it.format in supportedFormats }
                    ?.let { return ResolvedArtifact(it.url, it.checksum, it.format) }
            }

            artifacts.values
                .firstOrNull { it.format in supportedFormats }
                ?.let { return ResolvedArtifact(it.url, it.checksum, it.format) }
        }

        val fallbackFormat = inferFormatFromUrl(entry.url)
        if (fallbackFormat != null && fallbackFormat in supportedFormats) {
            return ResolvedArtifact(entry.url, entry.checksum, fallbackFormat)
        }
        return null
    }

    private fun inferFormatFromUrl(url: String): String? {
        val clean = url.substringBefore('?').substringBefore('#')
        val ext = clean.substringAfterLast('.', "")
        return ext.lowercase().takeIf { it.isNotBlank() }
    }
}

/**
 * Removes a plugin by its module id (multiplatform). Registered as `remove` and as `uninstall`.
 */
class PluginRemoveCommand(
    private val lister: InstalledPluginLister,
    name: String = "remove",
) : SuspendCliktCommand(name = name) {
    override fun help(context: Context) = "Remove an installed plugin"

    private val id by argument(help = "Plugin ID to remove")
    private val pluginDir by option(OPT_PLUGIN_DIR, help = "Plugin directory")

    override suspend fun suspendRun() {
        val targetDir = pluginDir ?: readManifestWarning().pluginDir ?: lister.defaultPluginDirPath()

        val removed = lister.removePlugin(id, targetDir)
        if (removed) {
            echo("✓ Removed plugin: $id")
        } else {
            echo("Error: Plugin not found: $id", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        lister.shutdown()
    }
}

/**
 * Top-level command for plugin cache management (multiplatform).
 * Subcommands are wired in platform-specific factories.
 */
class PluginCacheCommand : CliktCommand(name = "cache") {
    override fun help(context: Context) = "Manage the plugin download cache"

    override fun run() = Unit
}

/**
 * Clears the plugin download cache.
 */
class PluginCacheClearCommand :
    SuspendCliktCommand(
        name = "clear"
    ) {
    override fun help(context: Context) = "Clear all cached remote plugin downloads"

    override suspend fun suspendRun() {
        val cacheManager = CacheManager.defaultInstance()
        val cache = DownloadCache(casCache = cacheManager.getCasCache(CacheManager.DOWNLOADS_NAMESPACE))

        val removed = cache.clearAll()
        if (removed == 0) {
            echo("Plugin cache is already empty")
        } else {
            echo("Cleared $removed cached plugin(s)")
        }
    }
}

/**
 * Creates a new plugin project scaffold (multiplatform).
 * Generates a complete, compilable plugin project for the requested module type.
 */
class PluginCreateCommand : CliktCommand(name = "create") {
    override fun help(context: Context) = "Create a new plugin project scaffold"

    private val name by argument(
        help = "Plugin name (lowercase, hyphens allowed, e.g. my-custom-rule)"
    )

    private val type by option(
        "--type",
        help = "Plugin type: rule, reporter, provider, mutator, or comment-poster"
    ).default("rule")

    private val packageName by option(
        "--package",
        help = "Base package name for generated code (e.g. com.example.myplugin)"
    )

    private val noGit by option(
        "--no-git",
        help = "Skip git repository initialization"
    ).flag(default = false)

    override fun run() = scaffoldPlugin(name, type, packageName, initGit = !noGit)
}

/**
 * The one plugin scaffold behind `plugin create` and `generate`: validates [name] and [typeLabel], defaults the
 * package to `io.example.<name without hyphens>`, and writes `<name>-plugin/` under [baseDir].
 * Exits with [ExitCodes.VALIDATION_FAILED] on bad input or a generation failure.
 */
internal fun CliktCommand.scaffoldPlugin(
    name: String,
    typeLabel: String,
    packageName: String?,
    initGit: Boolean,
    baseDir: Path = Path("."),
) {
    val generator = PluginTemplateGenerator()
    generator.validatePluginName(name).onFailure { e ->
        echo("Error: ${e.message}", err = true)
        throw ProgramResult(ExitCodes.VALIDATION_FAILED)
    }
    val pluginType = TEMPLATE_TYPE_LABELS[typeLabel.lowercase()] ?: run {
        echo("Error: Invalid --type '$typeLabel'. Use: rule, reporter, provider, mutator, or comment-poster", err = true)
        throw ProgramResult(ExitCodes.VALIDATION_FAILED)
    }
    val config = TemplateConfig(
        name = name,
        packageName = packageName ?: "io.example.${name.replace("-", "")}",
        type = pluginType,
        initGit = initGit,
    )
    generator
        .generateProject(name, pluginType, config, baseDir)
        .onSuccess {
            echo("Created $typeLabel plugin: $name-plugin/")
            echo("")
            echo("Next steps:")
            echo("  1. cd $name-plugin")
            echo("  2. gradle build   (JDK 17+; README.md shows how to add a Gradle wrapper)")
            echo("  3. karandash plugin install build/libs/$name-plugin.jar")
        }.onFailure { e ->
            echo("Error creating plugin: ${e.message}", err = true)
            throw ProgramResult(ExitCodes.VALIDATION_FAILED)
        }
}
