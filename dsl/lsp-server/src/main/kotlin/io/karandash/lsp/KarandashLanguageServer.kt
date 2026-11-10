package io.karandash.lsp

import io.karandash.api.ModuleRegistry
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class KarandashLanguageServer(
    registry: ModuleRegistry,
    pipeline: ConfigPipelineDiagnostics? = null,
) : LanguageServer,
    LanguageClientAware {
    private val metadata = CachedRegistryMetadata(registry)
    private val diagnosticProvider = DiagnosticProvider(metadata, pipeline)
    private val textDocumentService = KarandashTextDocumentService(diagnosticProvider, metadata)
    private val workspaceService = KarandashWorkspaceService()
    private var shutdownRequested = false

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Full)
            completionProvider = CompletionOptions(false, listOf(".", "{", "=", "\""))
            hoverProvider = Either.forLeft(true)
        }
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun shutdown(): CompletableFuture<Any> {
        shutdownRequested = true
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        System.exit(if (shutdownRequested) 0 else 1)
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun connect(client: LanguageClient) {
        textDocumentService.connect(client)
    }
}
