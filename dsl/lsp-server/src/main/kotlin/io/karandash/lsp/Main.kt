package io.karandash.lsp

import io.karandash.bootstrap.PluginBootstrap
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import java.io.InputStream
import java.io.OutputStream

fun main() {
    val bootstrap = PluginBootstrap()
    // bridge-boundary: LSP server main() entry point
    runBlocking { bootstrap.loadPlugins() }
    startLanguageServer(bootstrap)
}

/** Serves the language server over [input]/[output] (stdio by default) until the client disconnects. */
fun startLanguageServer(
    bootstrap: PluginBootstrap,
    input: InputStream = System.`in`,
    output: OutputStream = System.out,
) {
    val server = KarandashLanguageServer(bootstrap.registry(), ConfigPipelineDiagnostics(bootstrap))
    val launcher = Launcher.createLauncher(server, LanguageClient::class.java, input, output)
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
}
