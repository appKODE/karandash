package io.karandash.cli

/** Environment variables that stand in for CLI options (read via the root context's `readEnvvar`). */
object CliEnvVars {
    /** Config file path for `--config` / `-c` when the option is not given. */
    const val CONFIG = "KARANDASH_CONFIG"

    /** `validate --strict` when set to a true value (`true`, `1`, `yes`, `on`). */
    const val STRICT = "KARANDASH_STRICT"
}
