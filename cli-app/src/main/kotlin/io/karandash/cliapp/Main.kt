package io.karandash.cliapp

import io.karandash.cli.runCli
import io.karandash.crashhandler.installCrashHandler
import io.karandash.lsp.startLanguageServer
import io.karandash.platform.platformExit

/** JVM entry point: the shared CLI plus `karandash lsp`, which only the JVM distribution bundles. */
fun main(args: Array<String>) {
    // Ktor/JGit pull in slf4j-api without a binding; without this every run starts with a "No SLF4J providers" banner.
    System.setProperty("slf4j.internal.verbosity", "ERROR")
    installCrashHandler()
    val exitCode = runCli(args) { bootstrap -> startLanguageServer(bootstrap) }
    platformExit(exitCode)
}
