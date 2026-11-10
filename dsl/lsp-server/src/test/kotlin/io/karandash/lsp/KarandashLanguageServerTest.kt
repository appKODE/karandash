package io.karandash.lsp

import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for KarandashLanguageServer server lifecycle and capabilities
 */
@Suppress("FunctionNaming")
class KarandashLanguageServerTest {
    private lateinit var server: KarandashLanguageServer

    @BeforeTest
    fun setup() {
        server = KarandashLanguageServer(TestRegistryFactory.createRegistry())
    }

    // --- Initialization Tests ---

    @Test
    fun `initialize returns correct server capabilities`() {
        val params = InitializeParams()
        val result = server.initialize(params).get()

        assertNotNull(result, "Initialize should return a result")
        assertNotNull(result.capabilities, "Capabilities should not be null")

        val capabilities = result.capabilities
        assertEquals(TextDocumentSyncKind.Full, capabilities.textDocumentSync.left)
        assertNotNull(capabilities.completionProvider, "Completion provider should be configured")
        assertNotNull(capabilities.hoverProvider, "Hover provider should be configured")
    }

    @Test
    fun `initialize sets completion trigger characters`() {
        val params = InitializeParams()
        val result = server.initialize(params).get()

        val completionProvider = result.capabilities.completionProvider
        assertNotNull(completionProvider, "Completion provider should not be null")

        val triggerChars = completionProvider.triggerCharacters
        assertNotNull(triggerChars, "Trigger characters should be set")
        assertTrue(triggerChars.contains("."), "Should trigger on .")
        assertTrue(triggerChars.contains("{"), "Should trigger on {")
        assertTrue(triggerChars.contains("="), "Should trigger on =")
        assertTrue(triggerChars.contains("\""), "Should trigger on \"")
    }

    @Test
    fun `initialize does not enable resolve provider`() {
        val params = InitializeParams()
        val result = server.initialize(params).get()

        val completionProvider = result.capabilities.completionProvider
        assertFalse(completionProvider.resolveProvider, "Resolve provider should not be enabled")
    }

    @Test
    fun `initialize returns hover capability as boolean true`() {
        val params = InitializeParams()
        val result = server.initialize(params).get()

        val hoverProvider = result.capabilities.hoverProvider
        assertTrue(hoverProvider.isLeft, "Hover provider should be a boolean")
        assertTrue(hoverProvider.left, "Hover provider should be enabled")
    }

    @Test
    fun `initialize completes successfully`() {
        val params = InitializeParams()
        val future = server.initialize(params)

        assertFalse(future.isCompletedExceptionally, "Initialize should not fail")
        assertTrue(future.isDone, "Initialize should complete immediately")
    }

    // --- Shutdown Tests ---

    @Test
    fun `shutdown completes successfully`() {
        val future = server.shutdown()

        assertTrue(future.isDone, "Shutdown should complete immediately")
        assertFalse(future.isCompletedExceptionally, "Shutdown should not fail")
    }

    @Test
    fun `shutdown returns null`() {
        val result = server.shutdown().get()
        assertNull(result, "Shutdown should return null")
    }

    @Test
    fun `shutdown can be called before initialize`() {
        val freshServer = KarandashLanguageServer(TestRegistryFactory.createRegistry())
        val result = freshServer.shutdown().get()
        assertNull(result, "Shutdown should work even if not initialized")
    }

    @Test
    fun `shutdown can be called multiple times`() {
        server.shutdown().get()
        val result = server.shutdown().get()
        assertNull(result, "Second shutdown should also succeed")
    }

    // --- Service Access Tests ---

    @Test
    fun `getTextDocumentService returns non-null service`() {
        val textDocService = server.textDocumentService
        assertNotNull(textDocService, "Text document service should not be null")
        assertTrue(textDocService is KarandashTextDocumentService, "Should be correct type")
    }

    @Test
    fun `getWorkspaceService returns non-null service`() {
        val workspaceService = server.workspaceService
        assertNotNull(workspaceService, "Workspace service should not be null")
        assertTrue(workspaceService is KarandashWorkspaceService, "Should be correct type")
    }

    @Test
    fun `getTextDocumentService returns same instance`() {
        val service1 = server.textDocumentService
        val service2 = server.textDocumentService
        assertSame(service1, service2, "Should return same instance")
    }

    @Test
    fun `getWorkspaceService returns same instance`() {
        val service1 = server.workspaceService
        val service2 = server.workspaceService
        assertSame(service1, service2, "Should return same instance")
    }

    // --- Client Connection Tests ---

    @Test
    fun `connect forwards client to text document service`() {
        val mockClient = MockLanguageClient()
        server.connect(mockClient)

        // Open a document to trigger diagnostics
        val textDocService = server.textDocumentService as KarandashTextDocumentService
        val params = DidOpenTextDocumentParams(
            TextDocumentItem("file:///test.kts", "karandash", 1, "rules {\n}")
        )
        textDocService.didOpen(params)

        // Verify client received diagnostics
        assertTrue(mockClient.diagnosticsPublished, "Client should have received diagnostics")
    }

    @Test
    fun `connect can be called before initialize`() {
        val freshServer = KarandashLanguageServer(TestRegistryFactory.createRegistry())
        val mockClient = MockLanguageClient()

        // Should not throw
        freshServer.connect(mockClient)
    }

    // --- Server Lifecycle Tests ---

    @Test
    fun `full lifecycle initialize-shutdown works`() {
        val initParams = InitializeParams()
        val initResult = server.initialize(initParams).get()
        assertNotNull(initResult)

        val shutdownResult = server.shutdown().get()
        assertNull(shutdownResult)
    }

    @Test
    fun `server can be used after initialize`() {
        val params = InitializeParams()
        server.initialize(params).get()

        val textDocService = server.textDocumentService
        assertNotNull(textDocService, "Services should be available after initialize")
    }

    @Test
    fun `server can be used after shutdown`() {
        server.shutdown().get()

        // Services should still be accessible (LSP allows graceful shutdown)
        val textDocService = server.textDocumentService
        assertNotNull(textDocService, "Services should still be accessible after shutdown")
    }

    // --- Thread Safety Tests ---

    @Test
    fun `multiple concurrent initialize calls complete`() {
        val params = InitializeParams()
        val futures = (1..10).map { server.initialize(params) }

        futures.forEach { future ->
            val result = future.get()
            assertNotNull(result, "All initialize calls should complete")
        }
    }

    @Test
    fun `multiple concurrent shutdown calls complete`() {
        val futures = (1..10).map { server.shutdown() }

        futures.forEach { future ->
            val result = future.get()
            assertNull(result, "All shutdown calls should complete")
        }
    }

    // --- Mock Language Client ---

    private class MockLanguageClient : LanguageClient {
        var diagnosticsPublished = false
        val publishedDiagnostics = mutableListOf<PublishDiagnosticsParams>()

        override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
            diagnosticsPublished = true
            publishedDiagnostics.add(diagnostics)
        }

        override fun logMessage(message: MessageParams) {}

        override fun showMessage(messageParams: MessageParams) {}

        override fun showMessageRequest(requestParams: ShowMessageRequestParams) = null

        override fun telemetryEvent(any: Any) {}
    }
}
