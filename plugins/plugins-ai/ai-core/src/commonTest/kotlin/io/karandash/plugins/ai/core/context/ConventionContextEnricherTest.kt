package io.karandash.plugins.ai.core.context

import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConventionContextEnricherTest {
    private fun testContext() = TestPluginContext(workingDirectory = "/tmp/repo")

    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test MR",
        description = null,
        author = User(username = "u", name = "U"),
        sourceBranch = "feat",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        changes = emptyList(),
        commits = emptyList(),
        approvals = emptyList(),
        reviewers = emptyList(),
    )

    private fun change(path: String): FileChange =
        FileChange(
            path = path,
            changeType = ChangeType.MODIFIED,
            additions = 1,
        )

    /** An enricher holding [files] as read from the MR base. */
    private fun enricherWith(files: Map<String, String>): ConventionContextEnricher =
        ConventionContextEnricher().also { it.useBaseFiles(files) }

    @Test
    fun `initialize returns true and shutdown is idempotent`() =
        runTest {
            val enricher = ConventionContextEnricher()
            assertTrue(enricher.initialize(testContext()))
            enricher.shutdown()
            enricher.shutdown()
        }

    @Test
    fun `provideBlock returns null when no base files were delivered`() =
        runTest {
            val enricher = ConventionContextEnricher()
            enricher.initialize(testContext())
            val result = enricher.provideBlock(
                mr = mr,
                changes = listOf(change("src/Main.kt")),
                config = JsonNull,
            )
            assertNull(result)
        }

    @Test
    fun `provideBlock returns null when not initialized`() =
        runTest {
            val enricher = enricherWith(mapOf(".editorconfig" to "indent_style = space"))
            val result = enricher.provideBlock(
                mr = mr,
                changes = listOf(change("src/Main.kt")),
                config = JsonNull,
            )
            assertNull(result)
        }

    @Test
    fun `provideBlock returns null when no convention files detected`() =
        runTest {
            val enricher = enricherWith(emptyMap())
            enricher.initialize(testContext())
            val result = enricher.provideBlock(
                mr = mr,
                changes = listOf(change("src/Main.kt")),
                config = JsonNull,
            )
            assertNull(result)
        }

    @Test
    fun `provideBlock produces block with editorconfig and contributing fixtures`() =
        runTest {
            val files = mapOf(
                ".editorconfig" to "indent_style = space\nindent_size = 4\nmax_line_length = 120\n",
                "CONTRIBUTING.md" to "# Code Review\nPlease follow the review checklist.\n",
            )
            val enricher = enricherWith(files)
            enricher.initialize(testContext())

            val result = enricher.provideBlock(
                mr = mr,
                changes = listOf(change("src/Main.kt")),
                config = JsonNull,
            )

            assertNotNull(result)
            assertEquals(ConventionContextEnricher.BLOCK_HEADING, result.heading)
            assertEquals(ConventionContextEnricher.BLOCK_PRIORITY, result.priority)
            assertTrue(result.content.contains("Code style"))
            assertTrue(result.content.contains("indent_style"))
            assertTrue(result.content.contains("Guidelines"))
        }

    @Test
    fun `redaction removes secret-like strings from convention file content`() =
        runTest {
            val secretKey = "AKIA0000000000000000"
            val files = mapOf(
                "CONTRIBUTING.md" to "# Code Review\nUse key $secretKey for testing.\n",
            )
            val enricher = enricherWith(files)
            enricher.initialize(testContext())

            val result = enricher.provideBlock(
                mr = mr,
                changes = listOf(change("src/Main.kt")),
                config = JsonNull,
            )

            assertNotNull(result)
            assertTrue(
                secretKey !in result.content,
                "Secret key should be redacted from convention content",
            )
        }

    @Test
    fun `provideBlock still runs with default enabled config`() =
        runTest {
            val files = mapOf(".editorconfig" to "indent_style = space")
            val enricher = enricherWith(files)
            enricher.initialize(testContext())

            val result = enricher.provideBlock(
                mr = mr,
                changes = listOf(change("src/Main.kt")),
                config = JsonNull,
            )

            assertNotNull(result, "conventionContext must stay on by default (unset config)")
        }

    @Test
    fun `provideBlock is skipped when explicitly disabled via nested contextQuality key`() =
        runTest {
            val files = mapOf(".editorconfig" to "indent_style = space")
            val enricher = enricherWith(files)
            enricher.initialize(testContext())

            val config = Json.parseToJsonElement(
                """{"contextQuality":{"conventionContext":{"enabled":false}}}""",
            )
            val result = enricher.provideBlock(
                mr = mr,
                changes = listOf(change("src/Main.kt")),
                config = config,
            )

            assertNull(result)
        }

    @Test
    fun `provideBlock is skipped when explicitly disabled via flat key`() =
        runTest {
            val files = mapOf(".editorconfig" to "indent_style = space")
            val enricher = enricherWith(files)
            enricher.initialize(testContext())

            val config = Json.parseToJsonElement("""{"conventionContext":{"enabled":false}}""")
            val result = enricher.provideBlock(
                mr = mr,
                changes = listOf(change("src/Main.kt")),
                config = config,
            )

            assertNull(result)
        }

    @Test
    fun `provideBlock runs when explicitly enabled`() =
        runTest {
            val files = mapOf(".editorconfig" to "indent_style = space")
            val enricher = enricherWith(files)
            enricher.initialize(testContext())

            val config = Json.parseToJsonElement("""{"conventionContext":{"enabled":true}}""")
            val result = enricher.provideBlock(
                mr = mr,
                changes = listOf(change("src/Main.kt")),
                config = config,
            )

            assertNotNull(result)
        }

    @Test
    fun `custom files listed as plain paths are injected`() =
        runTest {
            val enricher = enricherWith(mapOf("docs/STYLE.md" to "Use tabs"))
            enricher.initialize(testContext())

            val config = Json.parseToJsonElement("""{"conventionContext":{"files":["docs/STYLE.md"]}}""")
            val result = enricher.provideBlock(mr = mr, changes = listOf(change("src/Main.kt")), config = config)

            assertTrue(checkNotNull(result).content.contains("Use tabs"), result.content)
        }

    @Test
    fun `enrich returns empty map`() =
        runTest {
            val enricher = ConventionContextEnricher()
            enricher.initialize(testContext())
            val ctx = AiContextEnricherContext(ruleId = null, existingContext = emptyMap())
            val result = enricher.enrich(mr, ctx)
            assertTrue(result.isEmpty())
        }

    @Test
    fun `module metadata is correct`() {
        val enricher = ConventionContextEnricher()
        assertEquals(ConventionContextEnricher.MODULE_ID, enricher.id)
        assertEquals(ConventionContextEnricher.MODULE_NAME, enricher.name)
        assertEquals(ConventionContextEnricher.ENRICHER_PRIORITY, enricher.enricherPriority)
        assertTrue(enricher.defaultEnabled)
    }

    @Test
    fun `agent instruction files come from the base files`() =
        runTest {
            val enricher = enricherWith(AGENT_FILES)
            enricher.initialize(testContext())

            val block = assertNotNull(enricher.provideBlock(mr, listOf(change("src/Main.kt")), JsonNull))

            for ((path, text) in AGENT_FILES) assertTrue("Agent instructions ($path): $text" in block.content, block.content)
            assertNull(block.cutFromChars)
        }

    @Test
    fun `a file over the cap is left out whole and named as cut while the others are kept`() =
        runTest {
            val big = "Never do X.\n".repeat(ConventionContextEnricher.MAX_CHARS / 10)
            val enricher = enricherWith(mapOf("AGENTS.md" to big, "CLAUDE.md" to "Prefer val."))
            enricher.initialize(testContext())

            val block = assertNotNull(enricher.provideBlock(mr, listOf(change("src/Main.kt")), JsonNull))

            assertTrue("Prefer val." in block.content, block.content)
            assertTrue("Never do X." !in block.content, "the oversized file was cut instead of left out")
            assertTrue(checkNotNull(block.cutFromChars) > big.length)
        }

    @Test
    fun `base paths name the convention and agent files and the custom ones unless disabled`() {
        val enricher = ConventionContextEnricher()
        val custom = Json.parseToJsonElement("""{"conventionContext":{"files":["docs/STYLE.md"]}}""")
        val off = Json.parseToJsonElement("""{"conventionContext":{"enabled":false}}""")

        val paths = enricher.basePaths(custom)

        assertTrue(paths.containsAll(AGENT_FILES.keys + "CONTRIBUTING.md" + "docs/STYLE.md"), paths.toString())
        assertTrue(enricher.basePaths(off).isEmpty())
    }

    @Test
    fun `base files of one run do not leak into the next`() =
        runTest {
            val enricher = enricherWith(AGENT_FILES)
            enricher.initialize(testContext())
            enricher.shutdown()
            enricher.initialize(testContext())

            assertNull(enricher.provideBlock(mr, listOf(change("src/Main.kt")), JsonNull))
        }

    @Test
    fun `a lone file over the cap gives a blank block that only carries the ledger cut`() =
        runTest {
            val big = "Never do X.\n".repeat(ConventionContextEnricher.MAX_CHARS / 10)
            val enricher = enricherWith(mapOf("AGENTS.md" to big))
            enricher.initialize(testContext())

            val block = assertNotNull(enricher.provideBlock(mr, listOf(change("src/Main.kt")), JsonNull))

            assertEquals("", block.content)
            assertTrue(checkNotNull(block.cutFromChars) > big.length)
        }

    @Test
    fun `an empty custom file warns once naming it and an empty built-in file is silent`() =
        runTest {
            val log = testContext()
            val enricher = enricherWith(mapOf("docs/STYLE.md" to "  ", "CONTRIBUTING.md" to "", "CLAUDE.md" to "Prefer val."))
            enricher.initialize(log)
            val config = Json.parseToJsonElement("""{"conventionContext":{"files":["docs/STYLE.md"]}}""")

            enricher.provideBlock(mr, listOf(change("src/Main.kt")), config)
            enricher.provideBlock(mr, listOf(change("src/Main.kt")), config)

            assertEquals(
                listOf("ai.convention-context.files: 'docs/STYLE.md' is empty or missing on the base branch; skipped"),
                log.warnings,
            )
        }

    @Test
    fun `a custom file with content warns nothing`() =
        runTest {
            val log = testContext()
            val enricher = enricherWith(mapOf("docs/STYLE.md" to "Use tabs"))
            enricher.initialize(log)
            val config = Json.parseToJsonElement("""{"conventionContext":{"files":["docs/STYLE.md"]}}""")

            enricher.provideBlock(mr, listOf(change("src/Main.kt")), config)

            assertEquals(emptyList(), log.warnings)
        }

    private companion object {
        val AGENT_FILES = mapOf(
            "AGENTS.md" to "Use Result for platform calls.",
            "CLAUDE.md" to "No double-bang operator.",
            ".cursorrules" to "Keep functions short.",
            ".github/copilot-instructions.md" to "Prefer commonMain.",
        )
    }

    private val autoDetectOff = Json.parseToJsonElement(
        """{"contextQuality": {"conventions": {"autoDetect": false}}, "conventionContext": {"files": ["docs/rules.md"]}}""",
    )

    /** `ai.contextQuality.conventions.autoDetect: false` sends only the named `files`. */
    @Test
    fun `autoDetect false reads and sends only the named files`() =
        runTest {
            val enricher = enricherWith(mapOf(".editorconfig" to "indent_style = space", "docs/rules.md" to "No wildcard imports."))
            enricher.initialize(testContext())
            assertEquals(setOf("docs/rules.md"), enricher.basePaths(autoDetectOff))
            val block = assertNotNull(enricher.provideBlock(mr, listOf(change("src/Main.kt")), autoDetectOff))
            assertTrue("No wildcard imports." in block.content, block.content)
            assertTrue("indent_style" !in block.content, block.content)
        }

    @Test
    fun `unset autoDetect still detects the known convention files`() =
        runTest {
            val enricher = enricherWith(mapOf(".editorconfig" to "indent_style = space"))
            enricher.initialize(testContext())
            val block = assertNotNull(enricher.provideBlock(mr, listOf(change("src/Main.kt")), JsonNull))
            assertTrue("indent_style" in block.content, block.content)
        }
}
