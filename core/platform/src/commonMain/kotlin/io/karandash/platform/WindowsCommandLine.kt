package io.karandash.platform

/**
 * Builds a Windows command line that `CommandLineToArgvW` (and the MSVC runtime) splits back into the same argv.
 * Used with `CreateProcessW` directly, so no `cmd.exe` parses `&`, `|`, `<`, `>`, `^` or `%VAR%`.
 */
internal object WindowsCommandLine {
    private const val ARG_SPECIAL = " \t\n\u000B\""

    fun of(args: List<String>): String = args.joinToString(" ") { quote(it) }

    /** Quotes [arg] when needed: backslashes double only before a quote or the closing quote. */
    fun quote(arg: String): String {
        if (arg.isNotEmpty() && arg.none { it in ARG_SPECIAL }) return arg
        val body = buildString {
            val trailing = arg.fold(0) { backslashes, c ->
                when (c) {
                    '\\' -> backslashes + 1
                    '"' -> 0.also { append("\\".repeat(backslashes * 2 + 1)).append(c) }
                    else -> 0.also { append("\\".repeat(backslashes)).append(c) }
                }
            }
            append("\\".repeat(trailing * 2))
        }
        return "\"$body\""
    }
}
