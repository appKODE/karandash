package io.karandash.cli

import io.karandash.crashhandler.installCrashHandler
import io.karandash.platform.platformExit

/** Common entry point - delegates to the shared [runCli] implementation. */
fun main(args: Array<String>) {
    installCrashHandler()
    val exitCode = runCli(args)
    platformExit(exitCode)
}
