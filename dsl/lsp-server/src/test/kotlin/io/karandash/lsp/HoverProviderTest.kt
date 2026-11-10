package io.karandash.lsp

import org.eclipse.lsp4j.MarkupKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class HoverProviderTest {
    private companion object {
        private val RULES_TITLE_FORMAT_DOC = """
            rules {
                titleFormat {
                    pattern = "^(feat|fix): .+"
                    minLength = 10
                }
            }
        """.trimIndent()

        private val PROVIDERS_GITLAB_DOC = """
            platforms {
                gitlab {
                    url = "https://gitlab.com"
                    token = "secret"
                }
            }
        """.trimIndent()
    }

    private val hoverProvider = HoverProvider(CachedRegistryMetadata(TestRegistryFactory.createRegistry()))

    @Test
    fun `test hover on top-level block name`() {
        val document = RULES_TITLE_FORMAT_DOC

        // Hover on "rules" at line 0, character 2
        val hover = hoverProvider.getHover(document, 0, 2)

        assertNotNull(hover)
        assertEquals(MarkupKind.MARKDOWN, hover.contents.right.kind)
        assertTrue(
            hover.contents.right.value
                .contains("Validation Rules Configuration")
        )
        assertTrue(
            hover.contents.right.value
                .contains("titleFormat")
        )
    }

    @Test
    fun `test hover on validator name`() {
        val document = RULES_TITLE_FORMAT_DOC

        // Hover on "titleFormat" at line 1, character 6
        val hover = hoverProvider.getHover(document, 1, 6)

        assertNotNull(hover)
        assertEquals(MarkupKind.MARKDOWN, hover.contents.right.kind)
        assertTrue(
            hover.contents.right.value
                .contains("MR/PR Title Format Validator")
        )
        assertTrue(
            hover.contents.right.value
                .contains("pattern: String")
        )
        assertTrue(
            hover.contents.right.value
                .contains("minLength: Int")
        )
    }

    @Test
    fun `test hover on property name inside validator`() {
        val document = """
            rules {
                titleFormat {
                    pattern = "test"
                    minLength = 10
                }
            }
        """.trimIndent()

        // Hover on "pattern" at line 2, character 8 (start of word after indentation)
        val hover = hoverProvider.getHover(document, 2, 8)

        assertNotNull(hover)
        assertEquals(MarkupKind.MARKDOWN, hover.contents.right.kind)
        // Property doc comes from configSchema -- checks the property name and type
        assertTrue(
            hover.contents.right.value
                .contains("pattern"),
            "Should contain property name"
        )
        assertTrue(
            hover.contents.right.value
                .contains("String"),
            "Should contain type"
        )
    }

    @Test
    fun `test hover on minLength property`() {
        val document = """
            rules {
                titleFormat {
                    pattern = "test"
                    minLength = 10
                }
            }
        """.trimIndent()

        // Hover on "minLength" at line 3, character 8 (start of word after indentation)
        val hover = hoverProvider.getHover(document, 3, 8)

        assertNotNull(hover)
        assertEquals(MarkupKind.MARKDOWN, hover.contents.right.kind)
        assertTrue(
            hover.contents.right.value
                .contains("minLength"),
            "Should contain property name"
        )
        assertTrue(
            hover.contents.right.value
                .contains("Int"),
            "Should contain type"
        )
    }

    @Test
    fun `test hover on providers block`() {
        val document = PROVIDERS_GITLAB_DOC

        // Hover on "providers" at line 0, character 2
        val hover = hoverProvider.getHover(document, 0, 2)

        assertNotNull(hover)
        assertTrue(
            hover.contents.right.value
                .contains("Provider Configuration")
        )
        assertTrue(
            hover.contents.right.value
                .contains("gitlab")
        )
        assertTrue(
            hover.contents.right.value
                .contains("github")
        )
    }

    @Test
    fun `test hover on provider name`() {
        val document = PROVIDERS_GITLAB_DOC

        // Hover on "gitlab" at line 1, character 6
        val builtIn = HoverProvider(CachedRegistryMetadata(TestRegistryFactory.loadedBootstrap().registry()))
        val hover = builtIn.getHover(document, 1, 6)

        assertNotNull(hover)
        assertTrue(
            hover.contents.right.value
                .contains("GitLab Provider")
        )
    }

    @Test
    fun `test hover on reporters block`() {
        val document = """
            reporters {
                console = true
                comment = false
            }
        """.trimIndent()

        // Hover on "reporters" at line 0, character 2
        val hover = hoverProvider.getHover(document, 0, 2)

        assertNotNull(hover)
        assertTrue(
            hover.contents.right.value
                .contains("Reporting Configuration")
        )
        assertTrue(
            hover.contents.right.value
                .contains("console")
        )
        assertTrue(
            hover.contents.right.value
                .contains("slack")
        )
    }

    @Test
    fun `test hover on console reporter property`() {
        val document = """
            reporters {
                console = true
                comment = false
            }
        """.trimIndent()

        // Hover on "console" at line 1, character 6
        val builtIn = HoverProvider(CachedRegistryMetadata(TestRegistryFactory.loadedBootstrap().registry()))
        val hover = builtIn.getHover(document, 1, 6)

        assertNotNull(hover)
        assertTrue(
            hover.contents.right.value
                .contains("Console Reporter")
        )
        assertTrue(
            hover.contents.right.value
                .contains("Boolean")
        )
    }

    @Test
    fun `test hover on unknown symbol returns null`() {
        val document = """
            rules {
                titleFormat {
                    unknownProperty = "test"
                }
            }
        """.trimIndent()

        // Hover on "unknownProperty"
        val hover = hoverProvider.getHover(document, 2, 6)

        // Should return null for unknown symbols (no documentation available)
        assertNull(hover)
    }

    @Test
    fun `test hover on commitMessages validator`() {
        val document = """
            rules {
                commitMessages {
                    format = "conventional"
                    maxLength = 72
                }
            }
        """.trimIndent()

        // Hover on "commitMessages" at line 1, character 6
        val hover = hoverProvider.getHover(document, 1, 6)

        assertNotNull(hover)
        assertTrue(
            hover.contents.right.value
                .contains("Commit Message Validator")
        )
        assertTrue(
            hover.contents.right.value
                .contains("format: String")
        )
        assertTrue(
            hover.contents.right.value
                .contains("conventional")
        )
    }

    @Test
    fun `test hover on jiraTicket validator`() {
        val document = """
            rules {
                jiraTicket {
                    projectKeys = listOf("PROJ")
                    validateStatus = listOf("In Review")
                }
            }
        """.trimIndent()

        // Hover on "jiraTicket" at line 1, character 6
        val hover = hoverProvider.getHover(document, 1, 6)

        assertNotNull(hover)
        assertTrue(
            hover.contents.right.value
                .contains("JIRA Ticket Validator")
        )
        assertTrue(
            hover.contents.right.value
                .contains("projectKeys: List<String>")
        )
        assertTrue(
            hover.contents.right.value
                .contains("required")
        )
    }

    @Test
    fun `test hover on branchNaming validator`() {
        val document = """
            rules {
                branchNaming {
                    pattern = "^feature/.*"
                }
            }
        """.trimIndent()

        // Hover on "branchNaming" at line 1, character 6
        val hover = hoverProvider.getHover(document, 1, 6)

        assertNotNull(hover)
        assertTrue(
            hover.contents.right.value
                .contains("Branch Naming Validator")
        )
        assertTrue(
            hover.contents.right.value
                .contains("pattern: String")
        )
    }

    @Test
    fun `test hover on security validator`() {
        val document = """
            rules {
                security {
                    scanForSecrets = true
                    secretPatterns = listOf("API_KEY")
                }
            }
        """.trimIndent()

        // Hover on "security" at line 1, character 6
        val hover = hoverProvider.getHover(document, 1, 6)

        assertNotNull(hover)
        assertTrue(
            hover.contents.right.value
                .contains("Security Validator")
        )
        assertTrue(
            hover.contents.right.value
                .contains("scanForSecrets")
        )
    }

    @Test
    fun `test hover range is correct`() {
        val document = RULES_TITLE_FORMAT_DOC

        // Hover on "titleFormat" at line 1, character 6
        val hover = hoverProvider.getHover(document, 1, 6)

        assertNotNull(hover)
        val range = hover.range
        assertNotNull(range)
        // titleFormat starts at column 4 and ends at column 15
        assertEquals(1, range.start.line)
        assertEquals(4, range.start.character)
        assertEquals(1, range.end.line)
        assertEquals(15, range.end.character)
    }
}
