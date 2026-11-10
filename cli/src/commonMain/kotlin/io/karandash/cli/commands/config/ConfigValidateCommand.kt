package io.karandash.cli.commands.config

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.ConfigFileOptions
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.pipeline.ConfigLoader
import kotlinx.io.files.Path

/**
 * Validates a Karandash config file without running the validation pipeline.
 *
 * Loads the config exactly as `validate` does (extends chain, `{ file: }` refs, `${VAR}` interpolation)
 * and fails on parse errors. Unresolved environment variables and schema mismatches warn; `--strict` makes them fail.
 * Every `pomsky:` pattern is compiled with the linter on ([PomskyConfigCheck]): lint and complexity findings warn,
 * a compile error fails.
 *
 * Usage:
 * ```
 * karandash config validate
 * karandash config validate --file .karandash/config.yaml
 * karandash config validate --base   # shared base: consumer-only { file: } refs warn instead of failing
 * ```
 */
class ConfigValidateCommand(
    private val bootstrap: PluginBootstrap,
) : SuspendCliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate a Karandash config file for schema errors"

    private val configOptions by ConfigFileOptions(fileHelp = "Same as --config")
    private val file get() = configOptions.path
    private val strict by option("--strict", help = "Fail on unresolved environment variables and schema mismatches").flag()
    private val base by option(
        "--base",
        help = "Validate a shared base config on its own: a missing { file: } reference warns (consumers provide it)",
    ).flag()

    override suspend fun suspendRun() {
        val configFile = resolveConfigFile()
        if (configFile == null) {
            echo("No configuration file found. Run 'karandash init' to create one.", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        val result = ConfigCheck(bootstrap).check(configFile, strict = strict, base = base)
        result.messages.forEach {
            val prefix = if (it.severity == ConfigCheck.Severity.ERROR) "Config validation failed: " else "Warning: "
            echo(prefix + it.text, err = true)
        }
        if (result.failed) throw ProgramResult(ExitCodes.CONFIG_ERROR)
        echo("Config is valid: $configFile (${result.ruleCount} rule(s) configured)")
    }

    private fun resolveConfigFile(): Path? {
        val explicitFile = file
        if (explicitFile != null) {
            return Path(explicitFile)
        }
        return ConfigLoader.discover(bootstrap.formatRegistry).file
    }
}
