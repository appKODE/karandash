package io.karandash.cli.commands.generate

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.commands.scaffoldPlugin
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.pipeline.SchemaJsonGenerator
import io.karandash.platform.absolutePathOrSelf
import io.karandash.registry.TEMPLATE_TYPE_LABELS
import kotlinx.io.files.Path

/**
 * CLI command that generates plugin scaffolding or a JSON Schema for IDE autocompletion.
 *
 * @param bootstrap The plugin bootstrap used to access the module registry when generating schema.
 */
class GenerateCommand(
    private val bootstrap: PluginBootstrap,
) : SuspendCliktCommand(
        name = "generate"
    ) {
    override fun help(context: Context) =
        "Generate a JSON Schema (--schema), or plugin scaffolding (--type/--name; same as `karandash plugin create`)"

    private val generateSchema by option(
        "--schema",
        help = "Generate JSON Schema for IDE autocompletion and write to .karandash.schema.json"
    ).flag()

    private val type by option("--type", "-t")
        .choice(*TEMPLATE_TYPE_LABELS.keys.toTypedArray())

    private val name by option("--name", "-n")

    private val packageName by option(
        "--package",
        "-p",
        help = "Base package name for generated sources (default: io.example.<name without hyphens>)"
    )

    private val outputDir by option("--output", "-o")
        .default(".")

    private val initGit by option("--init-git", help = "Initialize a git repository in the generated project")
        .flag(default = false)

    override suspend fun suspendRun() {
        if (generateSchema) {
            SchemaJsonGenerator(bootstrap.registry()).writeToFile(".")
            echo("Schema written to .karandash.schema.json")
            return
        }

        val resolvedType = type ?: run {
            echo("Error: --type is required when not using --schema", err = true)
            throw ProgramResult(ExitCodes.VALIDATION_FAILED)
        }

        val resolvedName = name ?: run {
            echo("Error: --name is required when not using --schema", err = true)
            throw ProgramResult(ExitCodes.VALIDATION_FAILED)
        }

        scaffoldPlugin(resolvedName, resolvedType, packageName, initGit, Path(outputDir).absolutePathOrSelf())
    }
}
