package io.karandash.cli.commands.lsp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.GlobalWatchdog
import io.karandash.cli.errors.ExitCodes
import kotlin.time.Duration

/**
 * `karandash lsp`: serves the config language server over stdio for IDE extensions.
 *
 * [serve] is supplied by the JVM entry point (`cli-app`), which bundles `:dsl:lsp-server`; it is `null`
 * in native builds, where the command exits 6 (`EXECUTION_ERROR`).
 */
class LspCommand(
    private val bootstrap: PluginBootstrap,
    private val serve: ((PluginBootstrap) -> Unit)?,
) : CliktCommand(name = "lsp") {
    override fun help(context: Context) = "Run the config language server over stdio (JVM distribution only)"

    override fun run() {
        val launch = serve ?: run {
            echo("Error: the language server ships with the JVM distribution (karandash.jar) only", err = true)
            throw ProgramResult(ExitCodes.EXECUTION_ERROR)
        }
        // A language server lives as long as the editor session; the whole-run timeout does not apply.
        GlobalWatchdog.rearmActive(Duration.ZERO)
        launch(bootstrap)
    }
}
