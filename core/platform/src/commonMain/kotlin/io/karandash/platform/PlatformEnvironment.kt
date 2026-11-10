package io.karandash.platform

expect object PlatformEnvironment {
    fun getEnv(name: String): String?

    /**
     * Names of this process's environment variables, never their values. On native targets this reads `environ`,
     * so it must not race with `setenv` from another thread.
     */
    fun names(): Set<String>

    /**
     * The process stderr sink: the one sanctioned raw-output primitive for diagnostics. `CliLogger`-style
     * loggers write through it, and CLI startup uses it before a Logger exists. Module code logs via
     * `PluginContext.logger` instead; detekt `RawConsoleOutput` rejects raw println elsewhere.
     */
    fun stderrPrintln(message: String)

    /** Absolute path of the running executable (the native binary, or the `java` launcher on the JVM); `null` if unknown. */
    fun executablePath(): String?
}
