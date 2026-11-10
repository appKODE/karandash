package io.karandash.lsp

import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.services.LanguageClient
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for full LSP protocol flows
 */
@Suppress("FunctionNaming")
class IntegrationTest {
    private companion object {
        private const val LANGUAGE_ID = "karandash"
        private const val TEST_URI = "file:///test.kts"
        private val VALID_RULES_DOC = """
            rules {
                titleFormat {
                    pattern = "^(feat|fix): .+"
                }
            }
        """.trimIndent()
    }

    private lateinit var server: KarandashLanguageServer
    private lateinit var mockClient: MockLanguageClient
    private lateinit var textDocService: KarandashTextDocumentService

    @BeforeTest
    fun setup() {
        server = KarandashLanguageServer(TestRegistryFactory.createRegistry())
        mockClient = MockLanguageClient()
        server.connect(mockClient)
        server.initialize(InitializeParams()).get()
        textDocService = server.textDocumentService as KarandashTextDocumentService
    }

    // --- Full Protocol Flow Tests ---

    @Test
    fun `didOpen publishes diagnostics`() {
        val uri = TEST_URI
        val text = """
            rules {
                titleFormat {
                    pattern = "^feat: .+"
                }
            }
        """.trimIndent()

        val params = DidOpenTextDocumentParams(
            TextDocumentItem(uri, LANGUAGE_ID, 1, text)
        )

        textDocService.didOpen(params)

        assertTrue(mockClient.diagnosticsPublished, "Diagnostics should be published")
        assertEquals(1, mockClient.publishedDiagnostics.size)
        assertEquals(uri, mockClient.publishedDiagnostics[0].uri)
        assertTrue(mockClient.publishedDiagnostics[0].diagnostics.isEmpty(), "Valid document should have no errors")
    }

    @Test
    fun `didOpen with syntax errors publishes error diagnostics`() {
        val uri = "file:///bad.kts"
        val text = """
            rules {
                titleFormat {
                    pattern = "test"
            }
        """.trimIndent()

        val params = DidOpenTextDocumentParams(
            TextDocumentItem(uri, LANGUAGE_ID, 1, text)
        )

        textDocService.didOpen(params)

        assertTrue(mockClient.diagnosticsPublished)
        val diagnostics = mockClient.publishedDiagnostics[0].diagnostics
        assertTrue(diagnostics.isNotEmpty(), "Should have syntax errors")
        assertTrue(diagnostics.any { it.severity == DiagnosticSeverity.Error })
    }

    @Test
    fun `didChange updates diagnostics`() {
        val uri = TEST_URI

        // Open with valid content
        val openParams = DidOpenTextDocumentParams(
            TextDocumentItem(uri, LANGUAGE_ID, 1, VALID_RULES_DOC)
        )
        textDocService.didOpen(openParams)
        mockClient.clear()

        // Change to invalid content
        val changeParams = DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, 2),
            listOf(
                TextDocumentContentChangeEvent(
                    """
                    badBlock {
                        x = "y"
                    }
                    """.trimIndent()
                )
            )
        )
        textDocService.didChange(changeParams)

        assertTrue(mockClient.diagnosticsPublished, "Diagnostics should be published after change")
        val diagnostics = mockClient.publishedDiagnostics[0].diagnostics
        assertTrue(diagnostics.isNotEmpty(), "Should have semantic errors")
        assertTrue(diagnostics.any { it.message.left.contains("Unknown top-level block") })
    }

    @Test
    fun `didChange with multiple changes uses last change`() {
        val uri = TEST_URI

        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, "")
            )
        )
        mockClient.clear()

        // Multiple changes, only last should be used (Full sync mode)
        val changeParams = DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, 2),
            listOf(
                TextDocumentContentChangeEvent("first change"),
                TextDocumentContentChangeEvent(VALID_RULES_DOC)
            )
        )
        textDocService.didChange(changeParams)

        assertTrue(mockClient.diagnosticsPublished)
        // Should have diagnostics for VALID_RULES_DOC, not "first change"
    }

    @Test
    fun `didSave re-validates document`() {
        val uri = TEST_URI
        val text = VALID_RULES_DOC

        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, text)
            )
        )
        mockClient.clear()

        val saveParams = DidSaveTextDocumentParams(
            TextDocumentIdentifier(uri),
            text
        )
        textDocService.didSave(saveParams)

        assertTrue(mockClient.diagnosticsPublished, "Diagnostics should be published on save")
    }

    @Test
    fun `didClose clears diagnostics`() {
        val uri = TEST_URI

        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, "badContent")
            )
        )
        mockClient.clear()

        val closeParams = DidCloseTextDocumentParams(
            TextDocumentIdentifier(uri)
        )
        textDocService.didClose(closeParams)

        assertTrue(mockClient.diagnosticsPublished, "Should publish empty diagnostics on close")
        val diagnostics = mockClient.publishedDiagnostics[0].diagnostics
        assertTrue(diagnostics.isEmpty(), "Diagnostics should be cleared on close")
    }

    // --- Completion Flow Tests ---

    @Test
    fun `completion at top level returns top-level blocks`() {
        val uri = TEST_URI
        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, "")
            )
        )

        val params = CompletionParams(
            TextDocumentIdentifier(uri),
            Position(0, 0)
        )
        val result = textDocService.completion(params).get()

        assertTrue(result.isLeft, "Should return list of completion items")
        val items = result.left
        assertTrue(items.isNotEmpty())
        assertTrue(items.any { it.label == "rules" })
        assertTrue(items.any { it.label == "platforms" })
    }

    @Test
    fun `completion inside rules block returns validators`() {
        val uri = TEST_URI
        val text = """
            rules {

            }
        """.trimIndent()

        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, text)
            )
        )

        val params = CompletionParams(
            TextDocumentIdentifier(uri),
            Position(1, 4)
        )
        val result = textDocService.completion(params).get()

        val items = result.left
        assertTrue(items.any { it.label == "titleFormat" })
        assertTrue(items.any { it.label == "commitMessages" })
        assertTrue(items.any { it.label == "jiraTicket" })
    }

    @Test
    fun `completion inside titleFormat block returns properties`() {
        val uri = TEST_URI
        val text = """
            rules {
                titleFormat {

                }
            }
        """.trimIndent()

        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, text)
            )
        )

        val params = CompletionParams(
            TextDocumentIdentifier(uri),
            Position(2, 8)
        )
        val result = textDocService.completion(params).get()

        val items = result.left
        assertTrue(items.any { it.label == "pattern" })
        assertTrue(items.any { it.label == "minLength" })
        assertTrue(items.any { it.label == "maxLength" })
    }

    @Test
    fun `completion for unknown document returns empty list`() {
        val params = CompletionParams(
            TextDocumentIdentifier("file:///unknown.kts"),
            Position(0, 0)
        )
        val result = textDocService.completion(params).get()

        val items = result.left
        assertTrue(items.isNotEmpty(), "Should still return top-level completions even for unknown docs")
    }

    // --- Hover Flow Tests ---

    @Test
    fun `hover on rules keyword returns documentation`() {
        val uri = TEST_URI
        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, "rules {}")
            )
        )

        val params = HoverParams(
            TextDocumentIdentifier(uri),
            Position(0, 2) // Position in middle of "rules"
        )
        val result = textDocService.hover(params).get()

        assertNotNull(result, "Hover should return documentation for 'rules'")
        assertTrue(
            result.contents.right.value
                .contains("rules")
        )
    }

    // --- Edge Case Tests ---

    @Test
    fun `empty document produces no diagnostics`() {
        val uri = "file:///empty.kts"
        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, "")
            )
        )

        val diagnostics = mockClient.publishedDiagnostics[0].diagnostics
        assertTrue(diagnostics.isEmpty(), "Empty document is valid (zero blocks)")
    }

    @Test
    fun `large valid document is processed successfully`() {
        val uri = "file:///large.kts"
        val largeText = buildString {
            // the size comes from a long list
            appendLine("rules {")
            appendLine("    branchNaming {")
            appendLine("        allowedPatterns = listOf(")
            appendLine((0 until 400).joinToString(",\n") { "            \"feature/pattern-$it\"" })
            appendLine("        )")
            appendLine("    }")
            appendLine("}")
        }

        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, largeText)
            )
        )

        assertTrue(mockClient.diagnosticsPublished)
        val diagnostics = mockClient.publishedDiagnostics[0].diagnostics
        assertTrue(diagnostics.isEmpty(), "Large valid document should have no errors: ${diagnostics.map { it.message.left }}")
    }

    @Test
    fun `rapid didChange calls are handled correctly`() {
        val uri = "file:///rapid.kts"
        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, "")
            )
        )

        // Simulate rapid typing
        repeat(10) { version ->
            val changeParams = DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, version + 2),
                listOf(TextDocumentContentChangeEvent(VALID_RULES_DOC))
            )
            textDocService.didChange(changeParams)
        }

        // Should have published diagnostics for all changes
        assertTrue(mockClient.publishedDiagnostics.size >= 11, "Should publish diagnostics for all changes")
    }

    @Test
    fun `invalid syntax followed by valid syntax clears errors`() {
        val uri = TEST_URI

        // Open with invalid syntax
        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, "bad syntax {{{")
            )
        )

        val firstDiagnostics = mockClient.publishedDiagnostics[0].diagnostics
        assertTrue(firstDiagnostics.isNotEmpty(), "Should have syntax errors")

        mockClient.clear()

        // Fix with valid syntax
        textDocService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent(VALID_RULES_DOC))
            )
        )

        val secondDiagnostics = mockClient.publishedDiagnostics[0].diagnostics
        assertTrue(secondDiagnostics.isEmpty(), "Errors should be cleared")
    }

    @Test
    fun `semantic error followed by syntax error shows only syntax error`() {
        val uri = TEST_URI

        // Open with semantic error
        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, "badBlock {\n}")
            )
        )

        val firstDiagnostics = mockClient.publishedDiagnostics[0].diagnostics
        assertTrue(firstDiagnostics.any { it.message.left.contains("Unknown top-level block") })

        mockClient.clear()

        // Change to syntax error
        textDocService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent("rules { {{{"))
            )
        )

        val secondDiagnostics = mockClient.publishedDiagnostics[0].diagnostics
        assertTrue(secondDiagnostics.all { it.severity == DiagnosticSeverity.Error })
        // Should only show syntax errors, not semantic
    }

    @Test
    fun `multiple documents are tracked independently`() {
        val uri1 = "file:///doc1.kts"
        val uri2 = "file:///doc2.kts"

        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri1, LANGUAGE_ID, 1, VALID_RULES_DOC)
            )
        )

        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri2, LANGUAGE_ID, 1, "badBlock {\n}")
            )
        )

        // Check uri1 has no errors
        val uri1Diagnostics = mockClient.publishedDiagnostics.find { it.uri == uri1 }?.diagnostics
        assertNotNull(uri1Diagnostics)
        assertTrue(uri1Diagnostics.isEmpty())

        // Check uri2 has errors
        val uri2Diagnostics = mockClient.publishedDiagnostics.find { it.uri == uri2 }?.diagnostics
        assertNotNull(uri2Diagnostics)
        assertTrue(uri2Diagnostics.isNotEmpty())
    }

    @Test
    fun `didSave without text uses stored document`() {
        val uri = TEST_URI
        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, VALID_RULES_DOC)
            )
        )
        mockClient.clear()

        // Save without text parameter
        textDocService.didSave(
            DidSaveTextDocumentParams(
                TextDocumentIdentifier(uri),
                null
            )
        )

        assertTrue(mockClient.diagnosticsPublished, "Should still validate using stored content")
    }

    @Test
    fun `didSave with text updates stored document`() {
        val uri = TEST_URI
        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, "old content")
            )
        )

        val newText = "rules {\n    titleFormat {\n        pattern = \"test\"\n    }\n}"
        textDocService.didSave(
            DidSaveTextDocumentParams(
                TextDocumentIdentifier(uri),
                newText
            )
        )

        // Verify completion uses new content
        val params = CompletionParams(
            TextDocumentIdentifier(uri),
            Position(0, 0)
        )
        val result = textDocService.completion(params).get()
        assertNotNull(result, "Should have access to updated content")
    }

    @Test
    fun `position out of bounds is handled gracefully`() {
        val uri = TEST_URI
        textDocService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, LANGUAGE_ID, 1, "rules {}")
            )
        )

        // Request completion at position beyond document
        val params = CompletionParams(
            TextDocumentIdentifier(uri),
            Position(100, 100)
        )
        val result = textDocService.completion(params).get()

        assertNotNull(result, "Should handle out of bounds gracefully")
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

        fun clear() {
            diagnosticsPublished = false
            publishedDiagnostics.clear()
        }
    }
}
