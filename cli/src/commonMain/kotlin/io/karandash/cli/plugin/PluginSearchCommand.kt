package io.karandash.cli.plugin

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.karandash.api.ModuleType
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.manifest.ManifestData
import io.karandash.registry.REGISTRY_URL
import io.karandash.registry.RegistryClient
import io.karandash.registry.TEMPLATE_TYPE_LABELS
import io.karandash.utils.takeWhole
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Searches the karandash plugin registry.
 *
 * Usage:
 * - karandash plugin search jira
 * - karandash plugin search --tag integration
 * - karandash plugin search --type reporter
 * - karandash plugin search --type comment-poster
 * - karandash plugin search --refresh
 */
class PluginSearchCommand(
    private val registryClient: (ManifestData) -> RegistryClient = { it.registryClient() },
    private val manifestPath: Path = PLUGIN_MANIFEST_PATH,
) : SuspendCliktCommand(
        name = "search"
    ) {
    override fun help(context: Context) = "Search the karandash plugin registry"

    private val query by argument(
        help = "Search query (matches name, description, tags)"
    ).optional()

    private val tag by option(
        "--tag",
        help = "Filter by tag"
    )

    private val type by option(
        "--type",
        help = "Filter by plugin type"
    ).choice((ModuleType.entries.map { it.cliLabel } + TEMPLATE_TYPE_LABELS.keys).associateWith { it })

    private val refresh by option(
        "--refresh",
        help = "Force refresh the registry cache"
    ).flag(default = false)

    override suspend fun suspendRun() {
        val manifest = readPluginManifest(manifestPath) { echo("Warning: $it", err = true) }
        val client = registryClient(manifest)

        echo("Fetching plugin registry...")
        val registryResult = client.fetchRegistry(forceRefresh = refresh)
        val stale = if (registryResult.isFailure) client.staleCache() else null
        val error = registryResult.exceptionOrNull()
        if (error != null && stale == null) {
            echo("Error: Failed to fetch registry: ${error.message}", err = true)
            echo("", err = true)
            echo("Possible causes:", err = true)
            echo("  - No internet connection", err = true)
            echo("  - Registry URL is unreachable: ${manifest.registryUrl ?: REGISTRY_URL}", err = true)
            echo("  - Network proxy or firewall blocking access", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        if (error != null && stale != null) {
            val age = stale.second
                .coerceAtLeast(0)
                .milliseconds.inWholeMinutes.minutes
            echo("Warning: Failed to fetch registry: ${error.message}", err = true)
            echo("Warning: using cached registry from $age ago", err = true)
        }

        val registry = stale?.first ?: registryResult.getOrThrow()
        if (registry.plugins.isEmpty()) {
            echo("The plugin registry (${manifest.registryUrl ?: REGISTRY_URL}) lists no plugins yet.")
            echo("Install a plugin from a file instead: karandash plugin install <file>")
            return
        }

        val results = client.filterPlugins(registry, query, tag, type)

        if (results.isEmpty()) {
            echo("No plugins found matching your criteria.")
            if (query != null) {
                echo("Query: '$query'")
            }
            if (tag != null) {
                echo("Tag: '$tag'")
            }
            if (type != null) {
                echo("Type: '$type'")
            }
            return
        }

        val searchDesc = buildString {
            val filters = mutableListOf<String>()
            if (query != null) filters.add("\"$query\"")
            if (tag != null) filters.add("tag:$tag")
            if (type != null) filters.add("type:$type")

            if (filters.isEmpty()) {
                append("all plugins")
            } else {
                append(filters.joinToString(", "))
            }
        }

        echo("")
        echo("Found ${results.size} plugin(s) matching $searchDesc:")
        echo("")

        val nameWidth = maxOf(15, results.maxOfOrNull { it.name.length } ?: 15)
        val descWidth = maxOf(30, results.maxOfOrNull { it.description.length } ?: 30)
        val versionWidth = 10
        val authorWidth = maxOf(15, results.maxOfOrNull { it.author.length } ?: 15)

        printTableBorder(nameWidth, descWidth, versionWidth, authorWidth, isTop = true)
        printTableRow("Name", "Description", "Version", "Author", nameWidth, descWidth, versionWidth, authorWidth)
        printTableBorder(nameWidth, descWidth, versionWidth, authorWidth, isTop = false, isMiddle = true)

        results.forEach { plugin ->
            val truncatedDesc = if (plugin.description.length > descWidth) {
                plugin.description.takeWhole(descWidth - 3) + "..."
            } else {
                plugin.description
            }
            printTableRow(plugin.name, truncatedDesc, plugin.version, plugin.author, nameWidth, descWidth, versionWidth, authorWidth)

            echo("  Install: karandash plugin install --registry ${plugin.id}")
            echo("")
        }

        printTableBorder(nameWidth, descWidth, versionWidth, authorWidth, isTop = false)
    }

    private fun printTableBorder(
        nameWidth: Int,
        descWidth: Int,
        versionWidth: Int,
        authorWidth: Int,
        isTop: Boolean = false,
        isMiddle: Boolean = false
    ) {
        val left = if (isTop) {
            "┌"
        } else if (isMiddle) {
            "├"
        } else {
            "└"
        }
        val right = if (isTop) {
            "┐"
        } else if (isMiddle) {
            "┤"
        } else {
            "┘"
        }
        val cross = if (isTop) {
            "┬"
        } else if (isMiddle) {
            "┼"
        } else {
            "┴"
        }

        echo(
            "$left${"─".repeat(nameWidth + 2)}$cross${"─".repeat(descWidth + 2)}$cross${"─".repeat(versionWidth + 2)}$cross${"─".repeat(
                authorWidth + 2
            )}$right"
        )
    }

    private fun printTableRow(
        name: String,
        description: String,
        version: String,
        author: String,
        nameWidth: Int,
        descWidth: Int,
        versionWidth: Int,
        authorWidth: Int
    ) {
        val namePadded = name.padEnd(nameWidth)
        val descPadded = description.padEnd(descWidth)
        val versionPadded = version.padEnd(versionWidth)
        val authorPadded = author.padEnd(authorWidth)

        echo("│ $namePadded │ $descPadded │ $versionPadded │ $authorPadded │")
    }
}
