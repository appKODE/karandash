package io.karandash.platform

/**
 * POSIX `sh` fragments for running a command with variables removed from its environment at exec time.
 *
 * `exec env -u A -u B prog` replaces the shell, so neither the shell nor any wrapper between this process and
 * `prog` keeps the variables in its initial environment block. Programs are resolved with the shell's `PATH`
 * before `env` runs, since `PATH` itself may be one of the removed variables.
 */
internal object PosixUnsetEnv {
    /** `exec env -u ... ` (trailing space), or `exec ` when there is nothing to unset. */
    fun execPrefix(unsetEnv: Set<String>): String =
        if (unsetEnv.isEmpty()) "exec " else "exec env ${unsetEnv.joinToString(" ") { "-u ${quote(it)}" }} "

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * Resolves programs with the shell's `PATH` (before `env` removes it) into shell variables. A program that is
     * not found makes the shell print `<name>: not found` to the stderr file and exit 127, instead of `env`
     * failing on an empty command. Per-call: collect words with [program] / [commandLine], then prepend [prelude].
     *
     * @lifecycle per-run -- do not reuse across invocations
     */
    internal class Resolver(
        private val unsetEnv: Set<String>,
        private val stderrPath: String,
    ) {
        private val resolutions = StringBuilder()
        private var count = 0

        /** Statements to run before the command line; empty when nothing is unset. Ends with a space. */
        val prelude: String get() = resolutions.toString()

        /** [name] as a shell word: quoted, or the variable holding its resolved path when variables are unset. */
        fun program(name: String): String {
            if (unsetEnv.isEmpty()) return quote(name)
            val variable = "karandash_prog_${count++}"
            resolutions
                .append("$variable=\$(command -v ${quote(name)}) || { echo ${quote("$name: not found")} >&2; exit 127; } ")
                .append("2> ${quote(stderrPath)}; ")
            return "\"\$$variable\""
        }

        /** [command] as a shell command line, with its program resolved by [program]. */
        fun commandLine(command: List<String>): String = (listOf(program(command.first())) + command.drop(1).map(::quote)).joinToString(" ")
    }
}
