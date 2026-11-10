package io.karandash.cli.commands.init

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.templates.EmbeddedTemplates
import io.karandash.cli.templates.PresetComposer
import io.karandash.config.pipeline.ConfigSchemaVersion
import io.karandash.config.pipeline.SchemaJsonGenerator
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.createDirectories
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import io.karandash.plugins.parsers.json.JsonFormatModule
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Command to initialize a new karandash configuration file.
 *
 * Creates a minimal configuration in YAML, JSON, or KTS format.
 * For setup verification, use 'karandash doctor' after initialization.
 */
class InitCommand(
    private val sharedBootstrap: PluginBootstrap
) : SuspendCliktCommand(name = "init") {
    override fun help(context: Context) = "Initialize Karandash configuration"

    private val formatChoices: List<String> =
        sharedBootstrap.formatRegistry.allModules().map { it.formatId }

    private val outputOption by option(
        "--output",
        "-o",
        help = "Output path for configuration file (default: the chosen format's default path)",
    )

    private val format by option(
        "--format",
        "-f",
        help = "Config format (${formatChoices.joinToString("/")})"
    ).choice(formatChoices.associateWith { it })
        .default(YamlFormatModule.FORMAT_ID)

    private val profileId by option(
        "--profile",
        help = "Initialize config with a named profile (e.g. conventional-commits, github-flow)"
    )

    private val preset by option(
        "--preset",
        help = "Base template (${EmbeddedTemplates.keys("base").sorted().joinToString("/")})",
    ).choice(EmbeddedTemplates.keys("base").sorted())
        .default("minimal")

    private val platform by option(
        "--platform",
        help = "Platform template merged over the preset (${EmbeddedTemplates.keys("platforms").sorted().joinToString("/")})",
    ).choice(EmbeddedTemplates.keys("platforms").sorted())

    override suspend fun suspendRun() {
        val outputPath = outputOption ?: sharedBootstrap.formatRegistry.getRenderer(format).defaultOutputPath
        val outputFile = Path(outputPath).absolutePathOrSelf()
        if (SystemFileSystem.exists(outputFile)) {
            echo("Config file already exists at: $outputPath")
            echo("Tip: Run 'karandash doctor' to verify your setup")
            return
        }

        generateConfig(outputPath, format, profileId)
        echo("✅ Created $preset configuration at: $outputPath")
        echo("Tip: Run 'karandash doctor' to verify your setup")
    }

    private companion object {
        /** Schema file sits next to the config; `generate --schema` writes the same file. */
        const val SCHEMA_FILE = ".karandash.schema.json"
        const val SCHEMA_MODELINE = "# yaml-language-server: \$schema=$SCHEMA_FILE"
        const val SCHEMA_KEY = "\$schema"
        const val PROFILE_KEY = "profile"
        const val VERSION_KEY = "version"
    }

    private suspend fun generateConfig(
        path: String,
        format: String,
        profile: String?,
    ) {
        // templates carry their own `version`; init always writes the schema version this karandash supports
        val template = PresetComposer.compose(baseTemplate = preset, platformTemplate = platform)
        val composed = JsonObject(
            buildMap {
                if (format == JsonFormatModule.FORMAT_ID) put(SCHEMA_KEY, JsonPrimitive(SCHEMA_FILE))
                profile?.let { put(PROFILE_KEY, JsonPrimitive(it)) }
                put(VERSION_KEY, JsonPrimitive(ConfigSchemaVersion.CURRENT))
                putAll(template - VERSION_KEY)
            },
        )
        val configContent = sharedBootstrap.formatRegistry.getRenderer(format).renderConfig(composed)
        writeConfigFile(path, configContent)
    }

    private suspend fun writeConfigFile(
        path: String,
        content: String
    ) {
        val file = Path(path)
        val parent = file.safeParent
        if (parent != null) createDirectories(parent)
        file.writeText(if (format == YamlFormatModule.FORMAT_ID) "$SCHEMA_MODELINE\n$content" else content)
        // kts configs have no schema reference; yaml and json point at the file written next to the config
        if (format == YamlFormatModule.FORMAT_ID || format == JsonFormatModule.FORMAT_ID) writeSchemaNextTo(parent)
    }

    private suspend fun writeSchemaNextTo(parent: Path?) {
        try {
            SchemaJsonGenerator(sharedBootstrap.registry()).writeToFile(parent?.toString() ?: ".")
        } catch (e: IOException) {
            echo("Warning: could not write $SCHEMA_FILE: ${e.message}", err = true)
        }
    }
}
