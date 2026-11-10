package io.karandash.lsp

import org.eclipse.lsp4j.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class DiagnosticProviderTest {
    private companion object {
        private const val DIAGNOSTIC_SOURCE = "karandash"
    }

    private val provider = DiagnosticProvider(CachedRegistryMetadata(TestRegistryFactory.createRegistry()))

    // --- Syntax Error Tests ---

    @Test
    fun `valid config produces no diagnostics`() {
        val input = """
            rules {
                titleFormat {
                    pattern = "^(feat|fix): .+"
                    minLength = 10
                }
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertEquals(0, diagnostics.size, "Valid config should produce no diagnostics")
    }

    @Test
    fun `missing closing brace produces syntax error`() {
        val input = """
            rules {
                titleFormat {
                    pattern = "test"
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(diagnostics.isNotEmpty(), "Should detect missing closing brace")
        assertTrue(
            diagnostics.any { it.severity == DiagnosticSeverity.Error },
            "Should report as error severity"
        )
        assertTrue(
            diagnostics.all { it.source == DIAGNOSTIC_SOURCE },
            "Source should be 'karandash'"
        )
    }

    @Test
    fun `invalid token produces syntax error`() {
        val input = """
            rules {
                @@@ = "bad"
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(diagnostics.isNotEmpty(), "Should detect invalid tokens")
        assertTrue(diagnostics.any { it.severity == DiagnosticSeverity.Error })
    }

    @Test
    fun `empty input produces no diagnostics`() {
        val diagnostics = provider.computeDiagnostics("")
        assertEquals(0, diagnostics.size, "Empty input is valid (zero blocks)")
    }

    // --- Semantic Error Tests ---

    @Test
    fun `unknown top-level block produces error`() {
        val input = """
            foobar {
                x = "y"
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(diagnostics.isNotEmpty(), "Should detect unknown top-level block")
        assertTrue(
            diagnostics.any {
                it.severity == DiagnosticSeverity.Error && it.message.left.contains("Unknown top-level block 'foobar'")
            }
        )
    }

    @Test
    fun `unknown rule in rules block produces error`() {
        val input = """
            rules {
                unknownRule {
                    x = "y"
                }
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(diagnostics.isNotEmpty(), "Should detect unknown rule")
        assertTrue(
            diagnostics.any {
                it.severity == DiagnosticSeverity.Error && it.message.left.contains("Unknown rule 'unknownRule'")
            }
        )
    }

    @Test
    fun `duplicate top-level block produces warning`() {
        val input = """
            rules {
                titleFormat {
                    pattern = "test"
                }
            }
            rules {
                draftPrevention {
                    blockDraft = true
                }
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(
            diagnostics.any {
                it.severity == DiagnosticSeverity.Warning && it.message.left.contains("Duplicate top-level block 'rules'")
            }
        )
    }

    @Test
    fun `duplicate rule produces warning`() {
        val input = """
            rules {
                titleFormat {
                    pattern = "first"
                }
                titleFormat {
                    pattern = "second"
                }
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(
            diagnostics.any {
                it.severity == DiagnosticSeverity.Warning && it.message.left.contains("Duplicate rule 'titleFormat'")
            }
        )
    }

    @Test
    fun `missing required property in titleFormat produces error`() {
        val input = """
            rules {
                titleFormat {
                    minLength = 10
                }
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(
            diagnostics.any {
                it.severity == DiagnosticSeverity.Error && it.message.left.contains("Missing required property 'pattern'")
            }
        )
    }

    @Test
    fun `missing required property in jiraTicket produces error`() {
        val input = """
            rules {
                jiraTicket {
                    pattern = "test"
                }
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(
            diagnostics.any {
                it.severity == DiagnosticSeverity.Error &&
                    it.message.left.contains("Missing required property 'projectKeys'")
            }
        )
    }

    @Test
    fun `type mismatch in titleFormat produces error`() {
        val input = """
            rules {
                titleFormat {
                    pattern = "valid"
                    minLength = "not-a-number"
                }
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(
            diagnostics.any {
                it.severity == DiagnosticSeverity.Error && it.message.left.contains("Type mismatch")
            }
        )
    }

    @Test
    fun `unknown provider produces warning`() {
        val input = """
            platforms {
                unknownProvider {
                    token = "abc"
                }
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(
            diagnostics.any {
                it.message.left.contains("Unknown provider 'unknownProvider'")
            }
        )
    }

    @Test
    fun `valid providers block produces no diagnostics`() {
        val input = """
            platforms {
                gitlab {
                    url = "https://gitlab.com"
                    token = "secret"
                }
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertEquals(0, diagnostics.size)
    }

    @Test
    fun `unknown reporter property produces warning`() {
        val input = """
            reporters {
                unknownProp = true
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(
            diagnostics.any {
                it.severity == DiagnosticSeverity.Warning && it.message.left.contains("Unknown reporter property")
            }
        )
    }

    // --- LSP Format Tests ---

    @Test
    fun `diagnostics have correct line numbers`() {
        val input = """
            rules {
                badRule {
                    x = "y"
                }
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(diagnostics.isNotEmpty())

        val diagnostic = diagnostics.first { it.message.left.contains("Unknown rule") }
        // "badRule" is on line index 1 (0-based), column 4
        assertEquals(1, diagnostic.range.start.line, "Line should be 0-based")
        assertEquals(DIAGNOSTIC_SOURCE, diagnostic.source)
    }

    @Test
    fun `all diagnostics have source set to karandash`() {
        val input = """
            badBlock {
                x = "y"
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertTrue(diagnostics.isNotEmpty())
        diagnostics.forEach { diagnostic ->
            assertEquals(DIAGNOSTIC_SOURCE, diagnostic.source)
        }
    }

    @Test
    fun `complete valid config produces no diagnostics`() {
        val input = """
            rules {
                titleFormat {
                    pattern = "^(feat|fix): .+"
                    minLength = 10
                    maxLength = 72
                }
                commitMessages {
                    format = "conventional"
                    maxLength = 72
                }
                branchNaming {
                    pattern = "^(feature|bugfix|hotfix)/.+"
                }
                draftPrevention {
                    blockDraft = true
                }
                labels {
                    requiredLabels = listOf("reviewed")
                }
            }
            platforms {
                github {
                    token = "ghp_xxx"
                }
            }
            reporters {
                console = true
                markdown = true
            }
        """.trimIndent()

        val diagnostics = provider.computeDiagnostics(input)
        assertEquals(0, diagnostics.size, "Complete valid config should have no diagnostics")
    }
}
