package io.karandash.cli

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.option

/**
 * `--config/-c` (also `KARANDASH_CONFIG`) and its `--file/-f` alias, shared by the commands that load a config.
 * [fileHelp] shows the alias in help; without it the alias is hidden.
 */
class ConfigFileOptions(
    fileHelp: String? = null,
) : OptionGroup() {
    private val config by option("--config", "-c", help = "Config file path (default: auto-discover)", envvar = CliEnvVars.CONFIG)
    private val file by option("--file", "-f", help = fileHelp.orEmpty(), hidden = fileHelp == null)

    /** The config file given, or `null` to auto-discover. */
    val path: String? get() = config ?: file
}
