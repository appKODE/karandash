package io.karandash.cli.commands.config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

/**
 * Top-level `config` command grouping configuration management operations.
 *
 * Subcommands:
 * - `validate`  Validate a config file for schema errors without running validation.
 * - `describe`  Describe a config key or section.
 *
 * Usage:
 * ```
 * karandash config validate
 * karandash config describe rules
 * ```
 */
class ConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context) = "Configuration management commands"

    override fun run() = Unit
}
