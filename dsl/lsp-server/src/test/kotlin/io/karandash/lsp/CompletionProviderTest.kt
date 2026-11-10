package io.karandash.lsp

import io.karandash.config.schema.ConfigSections
import org.eclipse.lsp4j.CompletionItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("FunctionNaming")
class CompletionProviderTest {
    private companion object {
        private const val PROP_PATTERN = "pattern"
    }

    private val provider = CompletionProvider(CachedRegistryMetadata(TestRegistryFactory.createRegistry()))

    @Test
    fun `should provide top-level completions at start of file`() {
        val text = ""
        val completions = provider.getCompletions(text, 0, 0)

        val labels = completions.map { it.label }
        assertTrue(labels.contains("rules"))
        assertTrue(labels.contains("platforms"))
        assertTrue(labels.contains("reporters"))
        assertTrue(labels.contains("profile"))
        assertTrue(labels.contains("plugins"))
        assertEquals(ConfigSections.all.size, completions.size)
    }

    @Test
    fun `should provide validator completions inside rules block`() {
        val text = """
            rules {

            }
        """.trimIndent()

        val completions = provider.getCompletions(text, 1, 4)

        val labels = completions.map { it.label }
        assertTrue(labels.contains("titleFormat"))
        assertTrue(labels.contains("commitMessages"))
        assertTrue(labels.contains("jiraTicket"))
        assertTrue(labels.contains("branchNaming"))
        assertTrue(labels.contains("fileChanges"))
        assertTrue(labels.contains("codeowners"))
        assertTrue(labels.contains("approval"))
        assertTrue(labels.contains("reviewer"))
        assertTrue(labels.contains("cicdStatus"))
        assertTrue(labels.contains("conflict"))
        assertTrue(labels.contains("draftPrevention"))
        assertTrue(labels.contains("labels"))
        assertTrue(labels.contains("sizeLimits"))
        assertTrue(labels.contains("security"))
        assertTrue(labels.contains("contextTag"))
        assertTrue(labels.contains("descriptionFormat"))
        assertTrue(labels.contains("crossEntityConsistency"))
        assertTrue(labels.contains("languagePolicy"))
        assertTrue(labels.contains("commitBody"))
        assertTrue(labels.contains("ai"))
        assertTrue(labels.contains("jira"))
        assertTrue(labels.contains("dependencies"))
        assertEquals(22, completions.size)
    }

    @Test
    fun `should provide provider completions inside providers block`() {
        val text = """
            platforms {

            }
        """.trimIndent()

        val completions = provider.getCompletions(text, 1, 4)

        val labels = completions.map { it.label }
        assertTrue(labels.contains("gitlab"))
        assertTrue(labels.contains("github"))
        assertTrue(labels.contains("bitbucket"))
        assertTrue(labels.contains("gerrit"))
        assertTrue(labels.contains("azureDevOps"))
        assertTrue(labels.contains("awsCodeCommit"))
        assertTrue(labels.contains("gitea"))
        assertTrue(labels.contains("jenkins"))
        assertEquals(8, completions.size)
    }

    @Test
    fun `should provide property completions inside titleFormat block`() {
        val text = """
            rules {
                titleFormat {

                }
            }
        """.trimIndent()

        val completions = provider.getCompletions(text, 2, 8)

        val labels = completions.map { it.label }
        assertTrue(labels.contains(PROP_PATTERN))
        assertTrue(labels.contains("minLength"))
        assertTrue(labels.contains("maxLength"))
        assertTrue(labels.contains("requireTicket"))
        assertEquals(7, completions.size)
    }

    @Test
    fun `should provide property completions inside commitMessages block`() {
        val text = """
            rules {
                commitMessages {

                }
            }
        """.trimIndent()

        val completions = provider.getCompletions(text, 2, 8)

        val labels = completions.map { it.label }
        assertTrue(labels.contains("format"))
        assertTrue(labels.contains(PROP_PATTERN))
        assertTrue(labels.contains("minLength"))
        assertTrue(labels.contains("maxLength"))
        assertEquals(7, completions.size)
    }

    @Test
    fun `should provide property completions inside jiraTicket block`() {
        val text = """
            rules {
                jiraTicket {

                }
            }
        """.trimIndent()

        val completions = provider.getCompletions(text, 2, 8)

        val labels = completions.map { it.label }
        assertTrue(labels.contains("projectKeys"))
        assertTrue(labels.contains(PROP_PATTERN))
        assertTrue(labels.contains("validateStatus"))
        assertTrue(labels.contains("validateAssignee"))
        assertEquals(7, completions.size)
    }

    @Test
    fun `should provide reporter completions inside reporters block`() {
        val text = """
            reporters {

            }
        """.trimIndent()

        val completions = provider.getCompletions(text, 1, 4)

        val labels = completions.map { it.label }
        assertTrue(labels.contains("console"))
        assertTrue(labels.contains("markdown"))
        assertTrue(labels.contains("sarif"))
        assertTrue(labels.contains("slack"))
        assertTrue(labels.contains("reviewdog"))
        assertTrue(labels.contains("json"))
        assertTrue(labels.contains("email"))
        assertTrue(labels.contains("webhook"))
        assertEquals(8, completions.size)
    }

    @Test
    fun `should provide correct completion kinds`() {
        val text = """
            rules {
                titleFormat {

                }
            }
        """.trimIndent()

        val completions = provider.getCompletions(text, 2, 8)

        // All completions inside titleFormat should be properties
        assertTrue(completions.all { it.kind == CompletionItemKind.Property })
    }

    @Test
    fun `should include snippets with placeholders`() {
        val text = ""
        val completions = provider.getCompletions(text, 0, 0)

        val rulesCompletion = completions.find { it.label == "rules" }
        assertTrue(rulesCompletion != null)
        assertTrue(rulesCompletion.insertText!!.contains("{\n\t$0\n}"))
    }

    @Test
    fun `should handle nested blocks correctly`() {
        val text = """
            rules {
                titleFormat {
                    pattern = "test"
                }

            }
        """.trimIndent()

        // Cursor after titleFormat block, still inside rules
        val completions = provider.getCompletions(text, 4, 4)

        val labels = completions.map { it.label }
        // Should show validator completions, not property completions
        assertTrue(labels.contains("commitMessages"))
        assertTrue(labels.contains("jiraTicket"))
    }

    @Test
    fun `should provide property completions for all validator types`() {
        val validators = listOf(
            "branchNaming",
            "fileChanges",
            "codeowners",
            "approval",
            "reviewer",
            "cicdStatus",
            "conflict",
            "draftPrevention",
            "labels",
            "sizeLimits",
            "security",
            "contextTag",
            "descriptionFormat",
            "crossEntityConsistency",
            "languagePolicy",
            "commitBody",
            "ai",
            "jira",
            "dependencies",
        )

        validators.forEach { validatorName ->
            val text = """
                rules {
                    $validatorName {

                    }
                }
            """.trimIndent()

            val completions = provider.getCompletions(text, 2, 8)
            assertTrue(
                completions.isNotEmpty(),
                "Expected completions for $validatorName but got none"
            )
        }
    }
}
