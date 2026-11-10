package io.karandash.lsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture

class KarandashTextDocumentService(
    private val diagnosticProvider: DiagnosticProvider,
    private val metadata: CachedRegistryMetadata,
) : TextDocumentService {
    private val completionProvider = CompletionProvider(metadata)
    private val hoverProvider = HoverProvider(metadata)
    private val documentStore = MutableStateFlow<Map<String, String>>(emptyMap())
    private var client: LanguageClient? = null

    fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val text = params.textDocument.text
        documentStore.update { it + (uri to text) }
        publishDiagnostics(uri, text)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val text = params.contentChanges.lastOrNull()?.text ?: return
        documentStore.update { it + (uri to text) }
        publishDiagnostics(uri, text)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        documentStore.update { it - uri }
        client?.publishDiagnostics(
            PublishDiagnosticsParams(uri, emptyList())
        )
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = params.textDocument.uri
        val text = params.text ?: documentStore.value[uri] ?: return
        documentStore.update { it + (uri to text) }
        publishDiagnostics(uri, text)
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        val uri = params.textDocument.uri
        val line = params.position.line
        val character = params.position.character
        val documentText = documentStore.value[uri] ?: ""

        val completions = completionProvider.getCompletions(documentText, line, character)
        return CompletableFuture.completedFuture(Either.forLeft(completions))
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        val uri = params.textDocument.uri
        val line = params.position.line
        val character = params.position.character
        val documentText = documentStore.value[uri] ?: ""

        val hover = hoverProvider.getHover(documentText, line, character)
        return CompletableFuture.completedFuture(hover)
    }

    private fun publishDiagnostics(
        uri: String,
        text: String
    ) {
        val diagnostics = diagnosticProvider.computeDiagnostics(text, uri)
        client?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
    }
}
