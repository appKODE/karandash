package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.ai.pipeline.AiTrace
import io.karandash.api.support.ReporterDefaults
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.CliEnvVars
import io.karandash.cli.CliLogLevel
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.config.keys.ModuleIds

/**
 * `validate-local`: validates the local working copy (commits and diff of `HEAD` against `--target`) through the
 * local-git platform service. Takes the run flags of `validate` ([ValidateRunOptions], `--rules` among them);
 * `--fix`/`--yes` only warn, since local git has no API to apply fixes.
 *
 * Shorthand for `validate --platform local-git --project <target> --mr-id local`; no network, no token.
 */
class ValidateLocalCommand(
    private val sharedBootstrap: PluginBootstrap,
    private val initialLogLevel: CliLogLevel = CliLogLevel.NORMAL,
) : SuspendCliktCommand(name = "validate-local") {
    override fun help(context: Context) = "Validate local commits and diff against a target branch (no platform API)"

    private val configPathStr by option(
        "-c",
        "--config",
        help = "Path to configuration file - auto-discovered if not specified",
        envvar = CliEnvVars.CONFIG,
    )
    private val target by option("--target", help = "Target branch to diff against").default(DEFAULT_TARGET)
    private val format by option("-f", "--format").help { formatHelp(sharedBootstrap) }
    private val outputPathStr by option("-o", "--output", help = "Write output to file")
    private val strict by option("--strict", help = "Treat warnings as failures (exit code 5)", envvar = CliEnvVars.STRICT).flag()
    private val baselinePath by option(
        "--baseline",
        help = "JSON file of finding fingerprints to ignore; findings already in it are not reported",
    )
    private val updateBaseline by option("--update-baseline", help = "Rewrite the --baseline file from the current findings").flag()
    private val quiet by option("--quiet", "-q", help = "Suppress all output except errors").flag()
    private val verbose by option("--verbose", "-v", help = "Show detailed validation output").flag()
    private val trace by option(
        "--trace",
        help = "Verbose output plus full AI responses and per-rule AI diff file lists (same as ${AiTrace.ENV}=1)",
    ).flag()
    private val fix by option("--fix", help = "Not supported on local git (no platform API): warns and validates without fixing").flag()
    private val yes by option("--yes", "-y", help = "Not supported on local git: ignored").flag()
    private val run by ValidateRunOptions()

    override suspend fun suspendRun() {
        val logLevel = CliLogLevel.fromFlags(quiet, verbose || trace) ?: initialLogLevel
        if (trace) AiTrace.enable()
        val logger = CliLogger(quiet = logLevel.quiet, verbose = logLevel.verbose)
        sharedBootstrap.logger.configure(logger)
        if (fix || yes) {
            logger.warn("--fix is not supported by platform '${ModuleIds.LOCAL_GIT}' (no platform API); validating without fixes")
        }
        runMappingErrors(logger) {
            runValidation(options(logger, logLevel), run.watching, sharedBootstrap)
        }
    }

    private fun options(
        logger: CliLogger,
        logLevel: CliLogLevel,
    ) = ValidationOptions(
        configPathStr = configPathStr,
        mrDataStr = null,
        mrId = LOCAL_MR_ID,
        platform = ModuleIds.LOCAL_GIT,
        project = target,
        prUrl = null,
        format = format ?: ReporterDefaults.DEFAULT_FORMAT_ID,
        explicitFormat = format != null,
        outputPathStr = outputPathStr,
        quiet = logLevel.quiet,
        verbose = logLevel.verbose,
        strict = strict,
        baselinePath = baselinePath,
        updateBaseline = updateBaseline,
        dryRun = false,
        explain = false,
        fix = false,
        yes = false,
        noAutoDetect = true,
        outputWriter = ::echo,
        sharedBootstrap = sharedBootstrap,
        logger = logger,
    ).let(run::applyTo)

    private companion object {
        const val DEFAULT_TARGET = "main"
        const val LOCAL_MR_ID = "local"
    }
}
