package io.karandash.platform

actual object PlatformEnvironment {
    actual fun getEnv(name: String): String? = System.getenv(name)

    actual fun names(): Set<String> = System.getenv().keys.toSet()

    actual fun stderrPrintln(message: String) {
        System.err.println(message)
    }

    actual fun executablePath(): String? =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElse(null)
}
