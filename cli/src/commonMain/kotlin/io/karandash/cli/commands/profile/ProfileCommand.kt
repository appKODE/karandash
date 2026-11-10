package io.karandash.cli.commands.profile

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.module.ProfileModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.pipeline.splitProfileSeverity
import io.karandash.models.support.KarandashJson
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.createDirectories
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import io.karandash.sdk.config.PROFILE_RULE_SEVERITY_KEY
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private fun ModuleRegistry.findProfile(profileId: String): ProfileModule? =
    getByType(ModuleType.PROFILE)
        .filterIsInstance<ProfileModule>()
        .firstOrNull { it.profileId == profileId }

/**
 * Top-level command for profile management.
 * Subcommands: list, show, export.
 */
class ProfileCommand : CliktCommand(name = "profile") {
    override fun help(context: Context) = "Manage and explore configuration profiles"

    override fun run() = Unit
}

/**
 * Lists all registered profiles in a table.
 */
class ProfileListCommand(
    private val bootstrap: PluginBootstrap,
) : SuspendCliktCommand(name = "list") {
    private companion object {
        private const val SUMMARY_COLUMN_WIDTH = 50
        private const val TABLE_SEPARATOR_EXTRA_WIDTH = 20
    }

    override fun help(context: Context) = "List all available profiles"

    private val tag by option("--tag", help = "Filter profiles by tag")

    override suspend fun suspendRun() {
        val registry = bootstrap.registry()
        val profiles = registry
            .getByType(ModuleType.PROFILE)
            .filterIsInstance<ProfileModule>()
            .sortedBy { it.profileId }
            .let { all ->
                if (tag != null) all.filter { requireNotNull(tag) { "tag unexpectedly null" } in it.tags() } else all
            }

        if (profiles.isEmpty()) {
            echo(if (tag != null) "No profiles found with tag: $tag" else "No profiles registered")
            return
        }

        val idWidth = profiles.maxOf { it.profileId.length }.coerceAtLeast(10)
        val summaryWidth = SUMMARY_COLUMN_WIDTH
        echo(
            "PROFILE".padEnd(idWidth) + "  " +
                "SUMMARY".padEnd(summaryWidth) + "  " + "TAGS"
        )
        echo("-".repeat(idWidth + summaryWidth + TABLE_SEPARATOR_EXTRA_WIDTH))
        for (p in profiles) {
            val pTags = p.tags()
            val tags = if (pTags.isEmpty()) "" else "[${pTags.sorted().joinToString(", ")}]"
            val summary = p.summary.take(summaryWidth)
            echo(p.profileId.padEnd(idWidth) + "  " + summary.padEnd(summaryWidth) + "  " + tags)
        }
        echo("")
        echo("Run 'karandash profile show <id>' for details.")
    }
}

/**
 * Shows detailed info for one profile.
 */
class ProfileShowCommand(
    private val bootstrap: PluginBootstrap,
) : SuspendCliktCommand(name = "show") {
    override fun help(context: Context) = "Show details of a profile"

    private val profileId by argument(help = "Profile ID")

    override suspend fun suspendRun() {
        val registry = bootstrap.registry()
        val profile = registry.findProfile(profileId)

        if (profile == null) {
            echo(
                "Error: Unknown profile '$profileId'. Run 'karandash profile list' to see available profiles.",
                err = true
            )
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        echo("Profile:  ${profile.profileId}")
        echo("Summary:  ${profile.summary}")
        echo("Severity: ${profile.defaultSeverity.name.lowercase()} (default)")
        val profileTags = profile.tags()
        if (profileTags.isNotEmpty()) {
            echo("Tags:     ${profileTags.sorted().joinToString(", ")}")
        }
        echo("")
        echo("Rules configured:")
        val (rules, severities) = profile.ruleConfigs().splitProfileSeverity()
        if (rules.isEmpty()) {
            echo("  (none)")
        } else {
            for ((ruleId, config) in rules.entries.sortedBy { it.key }) {
                val jsonObject = config.jsonObject
                val params = jsonObject.entries.take(3).joinToString(", ") { (k, v) -> "$k: $v" }
                val ellipsis = if (jsonObject.size > 3) ", ..." else ""
                val severity = severities[ruleId]?.let { ", severity: $it" }.orEmpty()
                echo("  $ruleId  $params$ellipsis$severity")
            }
        }
        echo("")
        echo("To use: add 'profile: $profileId' to your .karandash/config.yaml")
        echo("Or run: karandash validate --profile $profileId <url>")
    }
}

/**
 * Exports a profile as a ready-to-use config file.
 * Supports YAML (default), JSON, and KTS output formats via `--format`.
 */
class ProfileExportCommand(
    private val bootstrap: PluginBootstrap,
) : SuspendCliktCommand(name = "export") {
    private companion object {
        private val prettyJson = KarandashJson.Pretty
    }

    override fun help(context: Context) = "Export a profile as a config file (yaml, json, or kts)"

    private val profileId by argument(help = "Profile ID to export")
    private val outputPath by option("--output", "-o", help = "Output file path (default: stdout)")

    private val format by option("--format", "-f", help = "Output format")
        .choice(
            bootstrap.formatRegistry
                .allRenderers()
                .associate { it.formatId to it.formatId },
        ).default("yaml")

    override suspend fun suspendRun() {
        val registry = bootstrap.registry()
        val profile = registry.findProfile(profileId)

        if (profile == null) {
            echo(
                "Error: Unknown profile '$profileId'. Run 'karandash profile list' to see available profiles.",
                err = true
            )
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        val output = when (format) {
            "json" -> renderJson(profile)
            "kts" -> renderKts(profile)
            else -> renderYaml(profile)
        }

        if (outputPath != null) {
            val path = Path(requireNotNull(outputPath) { "outputPath unexpectedly null" }).absolutePathOrSelf()
            val parent = path.safeParent
            if (parent != null) createDirectories(parent)
            path.writeText(output)
            echo("Profile '$profileId' exported to: $outputPath")
        } else {
            echo(output)
        }
    }

    private suspend fun renderYaml(profile: ProfileModule): String =
        buildString {
            appendLine("# Generated from profile: ${profile.profileId}")
            appendLine("# ${profile.summary}")
            appendLine("")
            appendLine("profile: ${profile.profileId}")
            appendLine("")
            appendLine("rules:")
            for ((ruleId, config) in profile.ruleConfigs().entries.sortedBy { it.key }) {
                // JSON scalars, arrays and objects are valid YAML flow values with correct quoting and escapes.
                val fields = exportFields(config)
                if (fields.isEmpty()) {
                    appendLine("  $ruleId: {}")
                } else {
                    appendLine("  $ruleId:")
                    fields.forEach { (key, value) -> appendLine("    $key: $value") }
                }
            }
        }

    private suspend fun renderJson(profile: ProfileModule): String {
        val rulesObj = buildJsonObject {
            for ((ruleId, config) in profile.ruleConfigs().entries.sortedBy { it.key }) {
                put(
                    ruleId,
                    buildJsonObject {
                        for ((key, value) in exportFields(config)) {
                            put(key, value)
                        }
                    }
                )
            }
        }
        val root = buildJsonObject {
            put("profile", profile.profileId)
            put("rules", rulesObj)
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    /** The profile's internal `@severity` marker is the rule's plain `severity:` key in an exported config. */
    private fun exportFields(config: JsonElement): JsonObject =
        JsonObject(config.jsonObject.mapKeys { (key, _) -> if (key == PROFILE_RULE_SEVERITY_KEY) "severity" else key })

    private suspend fun renderKts(profile: ProfileModule): String =
        buildString {
            appendLine("// Generated from profile: ${profile.profileId}")
            appendLine("// ${profile.summary}")
            appendLine("")
            appendLine("options {")
            appendLine("    profile = \"${profile.profileId}\"")
            appendLine("}")
            appendLine("")
            appendLine("rules {")
            for ((ruleId, config) in profile.ruleConfigs().entries.sortedBy { it.key }) {
                appendKtsBlock(ruleId, exportFields(config), "    ")
            }
            appendLine("}")
        }

    /** Nested objects become nested blocks; JSON-encoded literals carry the escapes the KTS STRING token expects. */
    private fun StringBuilder.appendKtsBlock(
        name: String,
        fields: JsonObject,
        indent: String,
    ) {
        appendLine("$indent$name {")
        for ((key, value) in fields) {
            when (value) {
                is JsonObject -> appendKtsBlock(key, value, "$indent    ")
                is JsonArray -> appendLine("$indent    $key = listOf(${value.joinToString(", ")})")
                else -> appendLine("$indent    $key = $value")
            }
        }
        appendLine("$indent}")
    }
}
