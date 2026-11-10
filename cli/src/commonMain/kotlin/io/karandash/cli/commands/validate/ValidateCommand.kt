package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.ai.pipeline.AiTrace
import io.karandash.api.exception.CliException
import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.CliEnvVars
import io.karandash.cli.CliLogLevel
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ErrorRenderer
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.cli.pipeline.ValidationPipeline
import io.karandash.models.MergeRequest
import io.karandash.platform.getCurrentTimestamp
import io.karandash.watcher.ConfigWatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Concrete implementation of the `validate` command shared across all targets.
 *
 * Delegates all validation logic to [ValidationPipeline] and handles only
 * CLI concerns: option parsing, watch mode, and exit codes.
 *
 * @param sharedBootstrap Optional pre-loaded bootstrap to reuse. When non-null, plugin loading
 *   is skipped and the provided registry/bootstrap is used directly. Ownership stays with the
 *   caller - this command will NOT call shutdown on it.
 * @param initialLogLevel Level pre-resolved from root flags / `KARANDASH_LOG_LEVEL`; this command's
 *   own `--quiet`/`--verbose` take precedence.
 */
class ValidateCommand(
    private val sharedBootstrap: PluginBootstrap,
    private val initialLogLevel: CliLogLevel = CliLogLevel.NORMAL,
) : SuspendCliktCommand(name = "validate") {
    private companion object {
        private val FORMAT_CONSOLE = io.karandash.api.support.ReporterDefaults.DEFAULT_FORMAT_ID
    }

    override fun help(context: Context) = "Validate a merge request against configured rules"

    private val configPathStr by option(
        "-c",
        "--config",
        "--rules-file",
        help = "Path to configuration file - auto-discovered if not specified (--rules-file is an alias)",
        envvar = CliEnvVars.CONFIG,
    )
    private val mrDataStr by option(
        "-m",
        "--mr",
        help = "Path to MR data file (.json) for file mode"
    )
    private val mrId by option("--mr-id", help = "MR/PR identifier for live mode")
    private val platform by option("--platform", help = "Platform name (gitlab, github, etc.) for live mode")
    private val project by option("--project", help = "Project identifier like \"group/repo\" for live mode")
    private val prUrl by option("--pr", help = "PR/MR URL for local testing mode (works without config file)")
    private val format by option("-f", "--format").help {
        formatHelp(sharedBootstrap) + " When set explicitly, only this reporter runs (secondary reporters are suppressed)."
    }
    private val run by ValidateRunOptions()
    private val outputPathStr by option("-o", "--output", help = "Write output to file")
    private val quiet by option("--quiet", "-q", help = "Suppress all output except errors").flag()
    private val verbose by option("--verbose", "-v", help = "Show detailed validation output").flag()
    private val trace by option(
        "--trace",
        help = "Verbose output plus full AI responses and per-rule AI diff file lists (same as ${AiTrace.ENV}=1)",
    ).flag()
    private val strict by option("--strict", help = "Treat warnings as failures (exit code 5)", envvar = CliEnvVars.STRICT).flag()
    private val baselinePath by option(
        "--baseline",
        help = "JSON file of finding fingerprints to ignore; findings already in it are not reported",
    )
    private val updateBaseline by option("--update-baseline", help = "Rewrite the --baseline file from the current findings").flag()
    private val showPrompt by option(
        "--show-prompt",
        help = "With --dry-run: build every AI prompt without sending anything and write each to ai.debug.promptDir " +
            "(the files hold MR source code; the directory gets a .gitignore of *)",
    ).flag()
    private val fix by option("--fix", help = "Automatically fix safe validation issues (labels, draft status)").flag()
    private val yes by option("--yes", "-y", help = "Skip confirmation prompts when using --fix").flag()
    private val noAutoDetect by option("--no-auto-detect", help = "Disable platform auto-detection").flag()
    private val allowLocalhost by option(
        "--allow-localhost",
        help = "Allow localhost and private IPs for providers/webhooks (disables SSRF protection)"
    ).flag()
    private val dangerfile by option(
        "--dangerfile",
        help = "Path to Dangerfile.js or Dangerfile.df.kts (auto-detected if not specified)"
    )
    private val enrich by option(
        "--enrich",
        help = "Generate AI title/description before validation (requires AI provider configured)"
    ).flag()
    private val dryRunEnrich by option(
        "--dry-run-enrich",
        help = "Preview AI-generated title/description without applying changes"
    ).flag()
    private val dryRunComment by option(
        "--dry-run-comment",
        help = "Print the comment body to console instead of posting it to the MR; thread replies and resolves are logged, not sent"
    ).flag()
    private val autoApprove by option(
        "--auto-approve",
        help = "Automatically approve the MR when all findings are below the severity threshold"
    ).flag()
    private val gateOnCached by option(
        "--gate-on-cached",
        help = "Count cached findings shown in the report (unchanged files) toward the exit code and auto-approve"
    ).flag()
    private val invalidateRules by option(
        "--invalidate-rules",
        help = "Comma-separated rule IDs to invalidate from the cache before validation"
    )

    init {
        // Declared for --help and parsing only: runWithGlobalTimeout reads it from the raw args to arm the
        // watchdog before Clikt runs (the timeout is measured from CLI start).
        registerOption(
            option(
                "--global-timeout",
                help = "Global watchdog timeout (e.g. 600s, 10m). Overrides config. 0 = no timeout."
            )
        )
    }

    override suspend fun suspendRun() {
        val logLevel = CliLogLevel.fromFlags(quiet, verbose || trace) ?: initialLogLevel
        if (trace) AiTrace.enable()
        val logger = CliLogger(quiet = logLevel.quiet, verbose = logLevel.verbose)
        sharedBootstrap.logger.configure(logger)

        if (run.watching && fix) {
            logger.error("--watch and --fix cannot be used together")
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        if (showPrompt && !run.dryRun) {
            logger.error("--show-prompt requires --dry-run")
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        runMappingErrors(logger) {
            runValidation(buildOptions(logger, logLevel), run.watching, sharedBootstrap)
        }
    }

    private fun buildOptions(
        logger: CliLogger,
        logLevel: CliLogLevel,
    ): ValidationOptions =
        ValidationOptions(
            configPathStr = configPathStr,
            mrDataStr = mrDataStr,
            mrId = mrId,
            platform = platform,
            project = project,
            prUrl = prUrl,
            format = format ?: FORMAT_CONSOLE,
            explicitFormat = format != null,
            outputPathStr = outputPathStr,
            quiet = logLevel.quiet,
            verbose = logLevel.verbose,
            strict = strict,
            baselinePath = baselinePath,
            updateBaseline = updateBaseline,
            dryRun = false,
            explain = false,
            fix = fix,
            yes = yes,
            noAutoDetect = noAutoDetect,
            outputWriter = ::echo,
            sharedBootstrap = sharedBootstrap,
            showPrompt = showPrompt,
            dangerfilePathOverride = dangerfile,
            allowLocalhost = allowLocalhost,
            enrich = enrich,
            dryRunEnrich = dryRunEnrich,
            dryRunComment = dryRunComment,
            autoApprove = autoApprove,
            gateOnCached = gateOnCached,
            invalidateRules = invalidateRules,
            logger = logger,
        ).let(run::applyTo)
}

/**
 * Runs [options] through the [ValidationPipeline]; with [watching], re-runs on every config change of the extends chain
 * (shared by `validate` and `validate-local`). A non-zero exit code ends the command with it.
 */
internal suspend fun runValidation(
    options: ValidationOptions,
    watching: Boolean,
    sharedBootstrap: PluginBootstrap,
) {
    val logger = options.logger
    val result = ValidationPipeline(options, sharedBootstrap).execute()
    if (watching) {
        if (result.usingZeroConfig || result.configFilePath == null) {
            logger.error("--watch mode requires a config file")
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        logger.info("\nWatching ${result.configFilePath} for changes... (Press Ctrl+C to stop)")
        startWatchMode(
            result.configFilePath,
            requireNotNull(result.mr) { "MR must be present in watch mode result" },
            options,
            result.config.display.watcher.effectiveDebounce.millis,
            result.extendsChainFiles,
            sharedBootstrap,
        )
    }
    if (result.exitCode != ExitCodes.SUCCESS) throw ProgramResult(result.exitCode)
}

/**
 * Enter watch mode, monitoring the config file for changes and re-validating.
 *
 * Each change runs the full [ValidationPipeline] on the already-fetched [mr], so watch output
 * matches a fresh run. Changes during a run coalesce into one more run ([SingleFlight]).
 * Uses [runBlocking] inside the [ConfigWatcher] callback because `ConfigWatcher.onChange`
 * is a plain `() -> Unit` lambda and cannot be made suspend.
 */
@Suppress("TooGenericExceptionCaught") // Watch mode error handler
private suspend fun startWatchMode(
    configFilePath: String,
    mr: MergeRequest,
    options: ValidationOptions,
    debounceMs: Long,
    initialChainFiles: List<String>,
    sharedBootstrap: PluginBootstrap,
) {
    val logger = options.logger
    val watchOptions = options.copy(configPathStr = configFilePath, mrOverride = mr)

    // the root file is always watched; a rerun that fails to load keeps the previous chain
    fun watched(chainFiles: List<String>): Set<String> = (chainFiles + configFilePath).toSet()
    coroutineScope {
        lateinit var chainWatch: ChainWatch
        val rerun = SingleFlight {
            val timestamp = getCurrentTimestamp()
            logger.info("\n[$timestamp] Config changed, re-validating...")
            try {
                // bridge-boundary: ConfigWatcher.onChange is a non-suspend callback
                val result = runBlocking { ValidationPipeline(watchOptions, sharedBootstrap).execute() }
                val status = if (result.exitCode == ExitCodes.SUCCESS) "PASSED" else "FAILED"
                logger.info("[$timestamp] Validation $status")
                chainWatch.update(watched(result.extendsChainFiles))
            } catch (e: Exception) {
                // boundary: watch mode re-validation may throw any exception
                e.rethrowCancellation()
                logger.error("[$timestamp] ${e.message}")
            }
            logger.info("\nWatching $configFilePath for changes... (Press Ctrl+C to stop)")
        }
        chainWatch = ChainWatch(this, debounceMs, logger) { rerun.trigger() }
        chainWatch.update(watched(initialChainFiles))
    }
}

/**
 * Runs [block] and maps pipeline exceptions to the `validate` exit codes (shared by `validate` and `validate-local`).
 */
@Suppress("TooGenericExceptionCaught") // Top-level CLI error handler
internal suspend fun runMappingErrors(
    logger: CliLogger,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: ProgramResult) {
        throw e
    } catch (e: io.karandash.api.errors.ConfigException) {
        logger.fail(withHint(e.message, e.hint), ExitCodes.CONFIG_ERROR)
    } catch (e: io.karandash.api.exception.ConfigParseException) {
        logger.fail(withHint(e.message, e.hint), ExitCodes.CONFIG_ERROR)
    } catch (e: io.karandash.api.errors.ProviderException) {
        logger.fail(e.message ?: FALLBACK_ERROR_MSG, ExitCodes.PROVIDER_ERROR)
    } catch (e: CliException) {
        logger.fail(e.message ?: FALLBACK_ERROR_MSG, e.exitCode)
    } catch (e: Exception) {
        // boundary: top-level CLI entry point catches all unhandled exceptions
        e.rethrowCancellation()
        logger.error("Internal error: ${e::class.simpleName}: ${e.message ?: "no message"}")
        logger.debug("Stack trace: ${e.stackTraceToString()}")
        throw ProgramResult(ExitCodes.INTERNAL_ERROR)
    }
}

/** Prints [message] through [ErrorRenderer.renderMessage] (catalog code, suggestion, docs link) and ends the run. */
private fun CliLogger.fail(
    message: String,
    exitCode: Int,
): Nothing {
    error(ErrorRenderer.renderMessage(message))
    throw ProgramResult(exitCode)
}

private const val FALLBACK_ERROR_MSG = "Unknown error"

/** The error [message], then the [hint] on its own `→ How to fix:` line when there is one; for every config error. */
internal fun withHint(
    message: String?,
    hint: String?,
): String = (message ?: FALLBACK_ERROR_MSG) + hint?.let { "\n  → How to fix: $it" }.orEmpty()

/**
 * Runs the blocking [ConfigWatcher.start] loop off the caller's thread and stops the watcher when the
 * calling coroutine is cancelled (`--global-timeout`, shutdown), so watch mode ends instead of blocking
 * forever (SS-3).
 */
internal suspend fun ConfigWatcher.watchUntilCancelled() {
    coroutineScope {
        val stopOnCancel = launch {
            try {
                awaitCancellation()
            } finally {
                stop()
            }
        }
        try {
            withContext(Dispatchers.IO) { start() }
        } finally {
            stopOnCancel.cancel()
            stop()
        }
    }
}
