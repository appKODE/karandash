package io.karandash.cli.commands.report

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

/**
 * Top-level `report` command grouping report management operations.
 *
 * Subcommands:
 * - `summary`  Print a summary of a validation result JSON file.
 *
 * Usage:
 * ```
 * karandash report summary --from results.json
 * ```
 */
class ReportCommand : CliktCommand(name = "report") {
    override fun help(context: Context) = "Report management commands"

    override fun run() = Unit
}
