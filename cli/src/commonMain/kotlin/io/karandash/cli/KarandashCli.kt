package io.karandash.cli

import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import io.karandash.Version
import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.ConfiguredRuleRunner
import io.karandash.cli.commands.cache.CacheClearCommand
import io.karandash.cli.commands.cache.CacheCommand
import io.karandash.cli.commands.cache.CacheGcCommand
import io.karandash.cli.commands.cache.CachePushCommand
import io.karandash.cli.commands.cache.CacheRemoteStatsCommand
import io.karandash.cli.commands.cache.CacheStatsCommand
import io.karandash.cli.commands.calibrate.CalibrateCommand
import io.karandash.cli.commands.config.ConfigCommand
import io.karandash.cli.commands.config.ConfigDescribeCommand
import io.karandash.cli.commands.config.ConfigValidateCommand
import io.karandash.cli.commands.configuredPluginDownloadCache
import io.karandash.cli.commands.dependencies.DepsCommand
import io.karandash.cli.commands.doctor.DoctorCommand
import io.karandash.cli.commands.explain.explainCommand
import io.karandash.cli.commands.format.FormatCommand
import io.karandash.cli.commands.format.FormatRenderCommand
import io.karandash.cli.commands.format.FormatSchemaCommand
import io.karandash.cli.commands.generate.GenerateCommand
import io.karandash.cli.commands.init.InitCommand
import io.karandash.cli.commands.lsp.LspCommand
import io.karandash.cli.commands.mr.MrCloseCommand
import io.karandash.cli.commands.mr.MrCommand
import io.karandash.cli.commands.mr.MrMergeCommand
import io.karandash.cli.commands.mr.MrReopenCommand
import io.karandash.cli.commands.mutate.MutateCommand
import io.karandash.cli.commands.pluginCommand
import io.karandash.cli.commands.profile.ProfileCommand
import io.karandash.cli.commands.profile.ProfileExportCommand
import io.karandash.cli.commands.profile.ProfileListCommand
import io.karandash.cli.commands.profile.ProfileShowCommand
import io.karandash.cli.commands.report.ReportCommand
import io.karandash.cli.commands.report.ReportSummaryCommand
import io.karandash.cli.commands.simulate.SimulateCommand
import io.karandash.cli.commands.stats.StatsCommand
import io.karandash.cli.commands.stats.loadAiConfig
import io.karandash.cli.commands.testrule.TestRuleCommand
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.commands.validate.ValidateExtendsCommand
import io.karandash.cli.commands.validate.ValidateLocalCommand
import io.karandash.cli.commands.verify.VerifyPolicyCommand
import io.karandash.cli.commands.wizard.WizardCommand
import io.karandash.cli.config.renderers.registerBuiltInRenderers
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.plugin.buildPluginCliCommands
import io.karandash.cli.plugin.loadPluginsWithManifest
import io.karandash.config.TimeoutConfig
import io.karandash.duration.KarandashDuration
import io.karandash.platform.PlatformEnvironment
import io.karandash.security.TextRedactor
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds

/**
 * Base CLI command with platform-independent subcommands.
 */
class KarandashCli :
    CliktCommand(
        name = "karandash"
    ) {
    override fun help(context: Context) = "MR validation tool with configurable rules"

    init {
        versionOption(Version.VERSION, names = setOf("--version", "-V"))
    }

    /** Read before parsing by [CliLogLevel.resolve]; declared so root-level placement is accepted. */
    val verbose by option("-v", "--verbose", "--debug", help = "Debug logging for every command, incl. plugin loading")
        .flag()

    /** Read before parsing by [CliLogLevel.resolve]; declared so root-level placement is accepted. */
    val quiet by option("-q", "--quiet", help = "Suppress all output except errors").flag()

    override fun run() = Unit
}

/**
 * Shared CLI entry point used by both JVM and Native `main()` functions.
 *
 * Wraps the entire CLI lifecycle in a single [runBlocking] scope with an
 * optional global watchdog timeout. On timeout the process exits
 * with diagnostic info instead of hanging.
 *
 * [dispatchCli] returns the exit code instead of exiting, so [PluginBootstrap.shutdown]
 * always runs before the platform `main()` exits.
 *
 * @param languageServer serves `karandash lsp`; `null` where the language server is not bundled (native).
 * @return process exit code (0 for success)
 */
fun runCli(
    args: Array<String>,
    languageServer: ((PluginBootstrap) -> Unit)? = null,
): Int =
    // bridge-boundary: process entry point, the one top-level CLI scope
    runBlocking {
        runWithGlobalTimeout(args) { executeMain(args, languageServer) }
    }

/**
 * Runs [main] under the global watchdog configured by `--global-timeout` in [args] (or `timeouts.global`
 * once the config re-arms it). Returns [ExitCodes.GLOBAL_TIMEOUT] when the watchdog fired first.
 */
internal suspend fun runWithGlobalTimeout(
    args: Array<String>,
    main: suspend () -> Int,
): Int {
    val cliTimeoutMs = resolveGlobalTimeoutMs(args)
    val watchdog = GlobalWatchdog(
        initial = (cliTimeoutMs ?: TimeoutConfig.DEFAULT_GLOBAL.millis).milliseconds,
        fixedByCli = cliTimeoutMs != null,
    )
    return watchdog.run { main() } ?: run {
        PlatformEnvironment.stderrPrintln(
            "[ERROR] Global timeout (${watchdog.current.inWholeSeconds}s) exceeded. " +
                "The tool may be stuck on a network request. " +
                "Use --diagnostic <path> to write runtime diagnostics (memory/CPU snapshots) to a file.",
        )
        ExitCodes.GLOBAL_TIMEOUT
    }
}

/**
 * Execute the main CLI flow: bootstrap, command dispatch, shutdown.
 */
private suspend fun executeMain(
    args: Array<String>,
    languageServer: ((PluginBootstrap) -> Unit)?,
): Int {
    val bootstrap = PluginBootstrap(
        onFormatRegistryCreated = ::registerBuiltInRenderers,
    )
    val envLevel = PlatformEnvironment.getEnv(CliLogLevel.ENV_VAR)
    // provisional: option values are only known once the tree exists, and the tree needs the loaded plugins
    val provisional = preconfigureLogging(bootstrap, args.asList(), envLevel)
    val loadResult = bootstrap.loadPluginsWithManifest()
    loadResult.failed.forEach { bootstrap.logger.warn(it.message) }

    suspend fun commandTree(level: CliLogLevel): KarandashCli {
        val pluginCmds = buildPluginCliCommands(bootstrap.registry(), bootstrap, RESERVED_COMMAND_NAMES, quiet = level.quiet)
        return karandashCommandTree(bootstrap, level, languageServer, pluginCmds)
    }

    try {
        val provisionalCli = commandTree(provisional)
        val logLevel = CliLogLevel.resolve(args.asList(), envLevel, valueOptionNames(provisionalCli))
        val cli = if (logLevel == provisional) {
            provisionalCli
        } else {
            // `--profile -q`: the `-q` was a value, not the flag
            bootstrap.logger.configure(CliLogger(quiet = logLevel.quiet, verbose = logLevel.verbose))
            commandTree(logLevel)
        }
        return dispatchCli(cli, args, logLevel)
    } finally {
        bootstrap.shutdown()
    }
}

/** Built-in command names a plugin command may not take; must list every subcommand of [karandashCommandTree]. */
internal val RESERVED_COMMAND_NAMES: Set<String> = setOf(
    "validate",
    "validate-local",
    "validate-extends",
    "init",
    "wizard",
    "doctor",
    "generate",
    "explain",
    "plugin",
    "profile",
    "stats",
    "calibrate",
    "test-rule",
    "verify-policy",
    "mutate",
    "deps",
    "cache",
    "config",
    "report",
    "format",
    "simulate",
    "lsp",
    "mr",
)

/**
 * Assemble the full command tree: built-in commands plus discovered plugin commands.
 */
internal fun karandashCommandTree(
    bootstrap: PluginBootstrap,
    logLevel: CliLogLevel,
    languageServer: ((PluginBootstrap) -> Unit)?,
    pluginCmds: List<CliktCommand>,
): KarandashCli =
    KarandashCli()
        .subcommands(
            ValidateCommand(bootstrap, logLevel),
            ValidateLocalCommand(bootstrap, logLevel),
            ValidateExtendsCommand(bootstrap, initialLogLevel = logLevel),
            InitCommand(bootstrap),
            WizardCommand(bootstrap),
            StatsCommand(aiConfig = { loadAiConfig(bootstrap) }, aiProviders = { bootstrap.registry().aiProviders() }),
            CalibrateCommand(aiConfig = { loadAiConfig(bootstrap) }),
            TestRuleCommand(ConfiguredRuleRunner.source(bootstrap), aiConfig = { loadAiConfig(bootstrap) }),
            VerifyPolicyCommand(ConfiguredRuleRunner.source(bootstrap)),
            SimulateCommand(bootstrap),
            MutateCommand(ConfiguredRuleRunner.source(bootstrap)),
            DepsCommand(bootstrap),
            DoctorCommand(bootstrap),
            explainCommand(bootstrap),
            GenerateCommand(bootstrap),
            ProfileCommand().subcommands(
                ProfileListCommand(bootstrap),
                ProfileShowCommand(bootstrap),
                ProfileExportCommand(bootstrap),
            ),
            ConfigCommand().subcommands(
                ConfigValidateCommand(bootstrap),
                ConfigDescribeCommand(bootstrap),
            ),
            ReportCommand().subcommands(
                ReportSummaryCommand(bootstrap),
            ),
            FormatCommand().subcommands(
                FormatSchemaCommand(bootstrap),
                FormatRenderCommand(bootstrap),
            ),
            CacheCommand().subcommands(
                CachePushCommand(bootstrap),
                CacheRemoteStatsCommand(bootstrap),
                CacheGcCommand(bootstrap),
                CacheStatsCommand(bootstrap),
                CacheClearCommand(bootstrap),
            ),
            MrCommand().subcommands(MrMergeCommand(bootstrap), MrCloseCommand(bootstrap), MrReopenCommand(bootstrap)),
            pluginCommand(downloadCache = { configuredPluginDownloadCache(bootstrap) }),
            LspCommand(bootstrap, languageServer),
        ).subcommands(pluginCmds)

/**
 * Parses [args] and runs the selected command of [cli], returning the process exit code.
 *
 * Unlike Clikt's `main()`, this never calls `exitProcess`, so the caller's `finally` (plugin shutdown)
 * still runs. The caller's [Job] is handed to [SuspendCliktCommand]s so the global watchdog can cancel them.
 * Last-resort handler: an unexpected exception becomes a one-line `[ERROR] Internal error` on stderr
 * (stack trace only with `--debug`/`--verbose`) and [ExitCodes.INTERNAL_ERROR]; cancellation propagates.
 */
@Suppress("TooGenericExceptionCaught") // boundary: last-resort handler for the whole CLI run
internal suspend fun dispatchCli(
    cli: CliktCommand,
    args: Array<String>,
    logLevel: CliLogLevel,
    stderr: (String) -> Unit = PlatformEnvironment::stderrPrintln,
): Int {
    val job = currentCoroutineContext()[Job]
    cli.context {
        if (job != null) obj = job
        // Option `envvar`s (KARANDASH_CONFIG, KARANDASH_STRICT) read through PlatformEnvironment.
        readEnvvar = PlatformEnvironment::getEnv
    }
    return try {
        cli.parse(args)
        ExitCodes.SUCCESS
    } catch (e: CliktError) {
        cli.echoFormattedHelp(e)
        // a usage error is CONFIG_ERROR (docs/reference/error-codes.md); Clikt's own status 1 would read as VALIDATION_FAILED
        if (e is UsageError) ExitCodes.CONFIG_ERROR else e.statusCode
    } catch (e: Exception) {
        // boundary: last-resort handler, cancellation rethrown
        e.rethrowCancellation()
        stderr(TextRedactor.redactForLog("[ERROR] Internal error: ${e::class.simpleName}: ${e.message ?: "no message"}"))
        stderr(if (logLevel.verbose) TextRedactor.redactForLog(e.stackTraceToString()) else "Run with --debug for the stack trace.")
        ExitCodes.INTERNAL_ERROR
    }
}

/**
 * Resolves the log level from raw [args] / [envLevel] and configures [bootstrap]'s logger at that level, NORMAL
 * included (warnings such as alias collisions and failed plugin loads reach stderr). Must run before
 * `loadPlugins()` so loader and lifecycle logs honour the level.
 */
internal fun preconfigureLogging(
    bootstrap: PluginBootstrap,
    args: List<String>,
    envLevel: String?,
    sink: (String) -> Unit = PlatformEnvironment::stderrPrintln,
): CliLogLevel {
    val logLevel = CliLogLevel.resolve(args, envLevel)
    bootstrap.logger.configure(CliLogger(quiet = logLevel.quiet, verbose = logLevel.verbose, sink = sink))
    return logLevel
}

/** Names of the options of [command] and its subcommands that take a value (`--profile x`), for [CliLogLevel.resolve]. */
internal fun valueOptionNames(command: BaseCliktCommand<*>): Set<String> =
    command.registeredOptions().filter { it.nvalues.last > 0 }.flatMapTo(mutableSetOf()) { it.names } +
        command.registeredSubcommands().flatMap { valueOptionNames(it) }

/**
 * Parse `--global-timeout` from raw CLI args before Clikt processes them.
 * Returns timeout in milliseconds (0 = no timeout), [TimeoutConfig.DEFAULT_GLOBAL] for an invalid value,
 * or null when the flag is absent (then `timeouts.global` may set it).
 */
internal fun resolveGlobalTimeoutMs(
    args: Array<String>,
    warn: (String) -> Unit = PlatformEnvironment::stderrPrintln,
): Long? {
    val argValue = args
        .asSequence()
        .zipWithNext()
        .firstOrNull { it.first == "--global-timeout" }
        ?.second
        ?: args
            .firstOrNull { it.startsWith("--global-timeout=") }
            ?.substringAfter("=")

    if (argValue != null) {
        return try {
            KarandashDuration.parse(argValue).millis
        } catch (e: IllegalStateException) {
            // KarandashDuration.parse reports bad input via error()
            invalidGlobalTimeout(argValue, e, warn)
        } catch (e: IllegalArgumentException) {
            invalidGlobalTimeout(argValue, e, warn)
        }
    }
    return null
}

private fun invalidGlobalTimeout(
    value: String,
    cause: Exception,
    warn: (String) -> Unit,
): Long {
    warn(
        "[WARN] Invalid --global-timeout '$value' (${cause.message}); " +
            "using default ${TimeoutConfig.DEFAULT_GLOBAL.millis / MILLIS_PER_SECOND}s.",
    )
    return TimeoutConfig.DEFAULT_GLOBAL.millis
}

private const val MILLIS_PER_SECOND = 1000L
