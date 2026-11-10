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
import io.karandash.manifest.ManifestData
import io.karandash.registry.InstalledPluginLister
import io.karandash.registry.PluginRegistryEntry
import io.karandash.registry.RegistryClient
import io.karandash.utils.VersionComparator

/**
 * Checks for and installs updates for installed plugins.
 *
 * Usage:
 * - karandash plugin update                     (check and update all)
 * - karandash plugin update io.karandash.rules  (update specific plugin)
 * - karandash plugin update --check-only        (only show available updates)
 * - karandash plugin update --yes               (skip confirmation)
 */
class PluginUpdateCommand(
    private val pluginLister: InstalledPluginLister,
    private val registryClient: (ManifestData) -> RegistryClient = { it.registryClient() },
    private val trustedKeys: () -> List<ByteArray> = { KeyManager().allPublicKeys() },
) : SuspendCliktCommand(
        name = "update"
    ) {
    override fun help(context: Context) = "Update installed plugins to latest versions"

    private companion object {
        const val MIN_PLUGIN_ID_COL_WIDTH = 20
        const val CUR_VER_COL_WIDTH = 15
        const val LAT_VER_COL_WIDTH = 15
        const val STATUS_COL_WIDTH = 20
    }

    private val pluginId by argument(
        help = "Plugin ID to update (omit to check all)"
    ).optional()

    private val checkOnly by option(
        "--check-only",
        "-c",
        help = "Only check for updates, don't install"
    ).flag()

    private val yes by option(
        "--yes",
        "-y",
        help = "Skip confirmation prompts"
    ).flag()

    private val pluginDirOpt by option(
        "--plugin-dir",
        help = "Plugin directory (default: ~/.karandash/plugins/)"
    )

    private val allowUnsigned by option(
        "--allow-unsigned",
        help = "Install a JAR update without a signature (<file>.sig) from a trusted key"
    ).flag(default = false)

    override suspend fun suspendRun() {
        val manifest = readPluginManifest { echo("Warning: $it", err = true) }
        val dirPath = pluginDirOpt ?: manifest.pluginDir ?: pluginLister.defaultPluginDirPath()

        if (!pluginLister.isDirectory(dirPath)) {
            echo("No plugins installed ($dirPath does not exist)")
            return
        }

        val installedModules = pluginLister.listInstalledPlugins(dirPath)
        if (installedModules.isEmpty()) {
            echo("No plugins installed in $dirPath")
            pluginLister.shutdown()
            return
        }

        if (pluginId != null) {
            val found = installedModules.any { it.id == pluginId }
            if (!found) {
                echo("Error: Plugin not found: $pluginId", err = true)
                echo("Use 'karandash plugin list' to see installed plugins", err = true)
                pluginLister.shutdown()
                throw ProgramResult(ExitCodes.CONFIG_ERROR)
            }
        }

        echo("Fetching plugin registry...")
        val client = registryClient(manifest)
        val registryResult = client.fetchRegistry()

        registryResult.onFailure { error ->
            echo("Error: Failed to fetch registry: ${error.message}", err = true)
            pluginLister.shutdown()
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        val registry = registryResult.getOrThrow()

        val modulesToCheck = if (pluginId != null) {
            installedModules.filter { it.id == pluginId }
        } else {
            installedModules
        }

        val updateInfos = modulesToCheck.map { module ->
            val registryEntry = registry.plugins.find { it.id == module.id }
            UpdateInfo(
                id = module.id,
                currentVersion = module.version,
                latestVersion = registryEntry?.version ?: module.version,
                registryEntry = registryEntry,
                sourcePath = module.sourcePath
            )
        }

        val updatesAvailable = printUpdateTable(updateInfos)

        if (updatesAvailable.isEmpty()) {
            echo("All plugins are up-to-date.")
            pluginLister.shutdown()
            return
        }

        echo("${updatesAvailable.size} update(s) available.")

        if (checkOnly) {
            pluginLister.shutdown()
            return
        }

        if (!yes) {
            echo("")
            echo("Update ${updatesAvailable.size} plugin(s)? [y/N] ", trailingNewline = false)
            val answer = readlnOrNull()?.trim()?.lowercase()
            if (answer != "y" && answer != "yes") {
                echo("Update cancelled.")
                pluginLister.shutdown()
                return
            }
        }

        installUpdates(updatesAvailable, dirPath, manifest.allowLocalhost)

        pluginLister.shutdown()
    }

    /** Prints the plugin/version/status table and returns the plugins with an installable update. */
    private fun printUpdateTable(updateInfos: List<UpdateInfo>): List<UpdateInfo> {
        val idWidth = maxOf(MIN_PLUGIN_ID_COL_WIDTH, updateInfos.maxOfOrNull { it.id.length } ?: MIN_PLUGIN_ID_COL_WIDTH)
        val curVerWidth = CUR_VER_COL_WIDTH
        val latVerWidth = LAT_VER_COL_WIDTH
        val statusWidth = STATUS_COL_WIDTH

        echo("")
        printTableBorder(idWidth, curVerWidth, latVerWidth, statusWidth, isTop = true)
        printTableRow(
            "Plugin ID",
            "Current",
            "Latest",
            "Status",
            idWidth,
            curVerWidth,
            latVerWidth,
            statusWidth
        )
        printTableBorder(idWidth, curVerWidth, latVerWidth, statusWidth, isMiddle = true)

        val updatesAvailable = mutableListOf<UpdateInfo>()

        for (info in updateInfos) {
            val status = when {
                info.registryEntry == null -> {
                    "not in registry"
                }

                VersionComparator.isNewer(info.currentVersion, info.latestVersion) &&
                    info.registryEntry.hostIncompatibility() != null -> {
                    "needs karandash ${info.registryEntry.minHostVersion}"
                }

                VersionComparator.isNewer(info.currentVersion, info.latestVersion) -> {
                    updatesAvailable.add(info)
                    "update available"
                }

                else -> {
                    "up-to-date"
                }
            }
            printTableRow(
                info.id,
                info.currentVersion,
                info.latestVersion,
                status,
                idWidth,
                curVerWidth,
                latVerWidth,
                statusWidth
            )
        }

        printTableBorder(idWidth, curVerWidth, latVerWidth, statusWidth)
        echo("")
        return updatesAvailable
    }

    private suspend fun installUpdates(
        updatesAvailable: List<UpdateInfo>,
        dirPath: String,
        allowLocalhost: Boolean,
    ) {
        echo("")
        var successCount = 0
        var failCount = 0

        for (info in updatesAvailable) {
            val entry = info.registryEntry ?: continue
            echo("Updating ${info.id} (${info.currentVersion} -> ${info.latestVersion})...")

            val downloadUrl = entry.url
            if (!downloadUrl.startsWith("https://")) {
                echo("  Error: Only HTTPS download URLs are allowed: $downloadUrl", err = true)
                failCount++
                continue
            }

            val result = pluginLister.downloadAndUpdate(
                downloadUrl = downloadUrl,
                pluginDirPath = dirPath,
                oldSourcePath = info.sourcePath,
                pluginId = info.id,
                expectedChecksum = entry.checksum,
                allowLocalhost = allowLocalhost,
                signatureKeys = if (allowUnsigned) null else trustedKeys(),
            )

            result
                .onSuccess { (backedUpName, _) ->
                    if (backedUpName != null) {
                        echo("  Backed up: $backedUpName")
                    }
                    echo("  Updated successfully.")
                    successCount++
                }.onFailure { error ->
                    echo("  Error: ${error.message}", err = true)
                    failCount++
                }
        }

        echo("")
        if (successCount > 0) {
            echo("$successCount plugin(s) updated successfully.")
        }
        if (failCount > 0) {
            echo("$failCount plugin(s) failed to update.")
            throw ProgramResult(ExitCodes.EXECUTION_ERROR)
        }
    }

    private class UpdateInfo(
        val id: String,
        val currentVersion: String,
        val latestVersion: String,
        val registryEntry: PluginRegistryEntry?,
        val sourcePath: String?
    )

    private fun printTableBorder(
        col1: Int,
        col2: Int,
        col3: Int,
        col4: Int,
        isTop: Boolean = false,
        isMiddle: Boolean = false,
    ) {
        val left = when {
            isTop -> "\u250C"
            isMiddle -> "\u251C"
            else -> "\u2514"
        }
        val right = when {
            isTop -> "\u2510"
            isMiddle -> "\u2524"
            else -> "\u2518"
        }
        val cross = when {
            isTop -> "\u252C"
            isMiddle -> "\u253C"
            else -> "\u2534"
        }
        val h = "\u2500"

        echo("$left${h.repeat(col1 + 2)}$cross${h.repeat(col2 + 2)}$cross${h.repeat(col3 + 2)}$cross${h.repeat(col4 + 2)}$right")
    }

    private fun printTableRow(
        col1: String,
        col2: String,
        col3: String,
        col4: String,
        w1: Int,
        w2: Int,
        w3: Int,
        w4: Int
    ) {
        echo("\u2502 ${col1.padEnd(w1)} \u2502 ${col2.padEnd(w2)} \u2502 ${col3.padEnd(w3)} \u2502 ${col4.padEnd(w4)} \u2502")
    }
}
