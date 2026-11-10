package io.karandash.cli

/**
 * CLI log verbosity, resolved from raw args before Clikt parses them so plugin loading
 * (which runs before any command) already logs at the requested level.
 *
 * Precedence: CLI flag (`-q/--quiet` > `-v/--verbose/--debug`) > [ENV_VAR] > [NORMAL].
 */
enum class CliLogLevel {
    QUIET,
    NORMAL,
    VERBOSE,
    ;

    val quiet: Boolean get() = this == QUIET
    val verbose: Boolean get() = this == VERBOSE

    companion object {
        /** Documented in `docs/reference/environment-variables.md`: `error|warn|info|debug|trace`. */
        const val ENV_VAR = "KARANDASH_LOG_LEVEL"

        private val QUIET_FLAGS = setOf("-q", "--quiet")
        private val VERBOSE_FLAGS = setOf("-v", "--verbose", "--debug")
        private const val END_OF_OPTIONS = "--"

        /** Level from explicit flags, or `null` when neither flag is set. */
        fun fromFlags(
            quiet: Boolean,
            verbose: Boolean,
        ): CliLogLevel? =
            when {
                quiet -> QUIET
                verbose -> VERBOSE
                else -> null
            }

        /** Level from [ENV_VAR]; `warn`/`info`/unknown map to `null` (default output). */
        fun fromEnv(value: String?): CliLogLevel? =
            when (value?.trim()?.lowercase()) {
                "error" -> QUIET
                "debug", "trace" -> VERBOSE
                else -> null
            }

        /**
         * Scans [args] (up to `--`) for log flags at any position, falling back to [envLevel]. A token right after
         * one of [valueOptions] is that option's value (`--profile -q`), not a flag.
         */
        fun resolve(
            args: List<String>,
            envLevel: String?,
            valueOptions: Set<String> = emptySet(),
        ): CliLogLevel {
            val options = args.takeWhile { it != END_OF_OPTIONS }
            val flags = options.filterIndexed { i, _ -> i == 0 || options[i - 1] !in valueOptions }
            // `validate extends -v` is local chain detail output, not global DEBUG logging (`--debug` still is)
            val localVerbose = options.windowed(2).any { it == listOf("validate", "extends") }
            return fromFlags(
                quiet = flags.any { it in QUIET_FLAGS },
                verbose = flags.any { it in VERBOSE_FLAGS && !(localVerbose && it != "--debug") },
            ) ?: fromEnv(envLevel) ?: NORMAL
        }
    }
}
