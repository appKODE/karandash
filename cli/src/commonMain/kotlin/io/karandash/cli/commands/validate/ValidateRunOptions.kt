package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.terminal.Terminal
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.platform.PlatformEnvironment

/** Run flags `validate` and `validate-local` share; [applyTo] copies them into the pipeline options. */
internal class ValidateRunOptions : OptionGroup() {
    private val watch by option("--watch", "-w", help = "Watch config file and re-run validation on changes").flag()
    private val once by option("--once", help = "Run a single validation and exit; overrides --watch").flag()
    val dryRun by option("--dry-run", help = "Validate config without running validation").flag()
    private val explain by option("--explain", help = "Show detailed explanations for each finding").flag()
    private val style by option(
        "--style",
        help = "Console layout: concise (default; findings grouped by file, summary last) or full (every detail per finding)",
    ).choice("concise", "full")
    private val noColor by option("--no-color", help = "Never colour console output").flag()
    private val profile by option(
        "--profile",
        help = "Apply a named profile (e.g. conventional-commits, github-flow). Overrides profile in config file.",
    )
    private val showExecutionPlan by option(
        "--show-execution-plan",
        help = "Print validation rules grouped by execution priority and exit",
    ).flag()
    private val noCache by option(
        "--no-cache",
        help = "Disable staleness gate cache — force full re-run of all rules (state is still saved for the next run)",
    ).flag()
    private val diagnostic by option(
        "--diagnostic",
        help = "Write JSON runtime diagnostics (memory/CPU snapshots, timeline) to the given file path",
    )
    private val dumpDir by option(
        "--dump-dir",
        help = "Save every layer input/output of the run (config, MR, findings per stage, AI plans and calls) as files " +
            "in this directory. Local debugging only: files contain MR source and diffs (secrets redacted best-effort)",
    )
    private val onMissingPlugin by option(
        "--on-missing-plugin",
        help = "How to handle config references to uninstalled modules: fail, warn (default), ignore",
    ).choice("fail", "warn", "ignore", ignoreCase = true)
    private val rules by option("--rules", help = "Comma-separated rule IDs to run (default: all configured rules)")

    /** `--watch` without `--once`. */
    val watching: Boolean get() = watch && !once

    fun applyTo(options: ValidationOptions): ValidationOptions =
        options.copy(
            dryRun = dryRun,
            explain = explain,
            style = style,
            color = !noColor && colorTerminal(),
            profileId = profile,
            showExecutionPlan = showExecutionPlan,
            noCache = noCache,
            diagnosticPath = diagnostic ?: dumpDir?.let { "$it/diagnostic.json" },
            dumpDir = dumpDir,
            onMissingPlugin = onMissingPlugin,
            liveMode = watching,
            onlyRules = rules
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet(),
        )
}

/** True when stdout is a terminal and neither `NO_COLOR` (any non-empty value) nor `TERM=dumb` asks for plain text. */
internal fun colorTerminal(): Boolean =
    PlatformEnvironment.getEnv("NO_COLOR").isNullOrEmpty() &&
        PlatformEnvironment.getEnv("TERM") != "dumb" &&
        Terminal().terminalInfo.outputInteractive

/** `--format` help naming every format a run can use: the registered format modules and reporters. */
internal fun formatHelp(bootstrap: PluginBootstrap): String {
    val registry = bootstrap.registry()
    val ids = (registry.formats().map { it.formatId } + registry.reporters().map { it.formatId }).distinct().sorted()
    return "Output format ID: ${ids.joinToString(", ")}."
}
