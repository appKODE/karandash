package io.karandash.integration.rules.ai

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.FindingLocationValidator
import io.karandash.plugins.ai.core.context.ConventionContextEnricher
import io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for AI Context Quality pipeline features.
 *
 * Exercises the two critical components of the context quality pipeline together:
 * 1. **Enrichers** -- [ConventionContextEnricher] produces structured [AiContextBlock]s
 *    that are registered in and retrieved from [ModuleRegistryImpl].
 * 2. **FindingLocationValidator** -- drops AI-hallucinated findings whose paths or
 *    lines do not correspond to real MR changes.
 *
 * The full `AiRuleModule.validate()` pipeline is not exercised here because
 * [AiPromptAssembler] is `internal` to `:plugins:plugins-ai:rules:ai` and the
 * module's initialization requires provider resolution, token budgets, and state
 * persistence that are impractical to wire in a cross-module integration test.
 * Both critical components (prompt-enrichment and finding-validation) are tested
 * together in a cohesive scenario to verify end-to-end correctness.
 */
class AiContextQualityPipelineIntegrationTest {
    // -- Shared test fixtures --

    private val diffContent = "@@ -0,0 +1,3 @@\n+line 1\n+line 2\n+line 3\n"

    private val realFileChange = FileChange(
        path = "src/Real.kt",
        changeType = ChangeType.ADDED,
        additions = 3,
        diffContent = diffContent,
    )

    private val testMr: MergeRequest = createTestMergeRequest(
        title = "Add Real.kt",
        changes = listOf(realFileChange),
    )

    // -- Tests --

    @Test
    fun `FindingLocationValidator drops hallucinated path and keeps valid finding`() {
        val validFinding = Finding(
            severity = Severity.WARNING,
            message = "Potential null dereference",
            rule = "ai-review-comments",
            location = Location.FileRef(path = "src/Real.kt", line = 2),
        )
        val hallucinatedFinding = Finding(
            severity = Severity.WARNING,
            message = "Missing error handling",
            rule = "ai-review-comments",
            location = Location.FileRef(path = "src/DoesNotExist.kt", line = 42),
        )
        val mrLevelFinding = Finding(
            severity = Severity.INFO,
            message = "Consider adding tests",
            rule = "ai-review-comments",
            location = null,
        )

        val validated = FindingLocationValidator.validate(
            findings = listOf(validFinding, hallucinatedFinding, mrLevelFinding),
            mr = testMr,
            ruleId = "ai-review-comments",
            strict = true,
            diagnostics = ContextQualityDiagnostics(NoOpLogger),
        )

        assertEquals(2, validated.size, "Expected valid + MR-level findings only")
        assertTrue(
            validated.any {
                (it.location as? Location.FileRef)?.path == "src/Real.kt"
            },
            "Valid finding at src/Real.kt should be kept",
        )
        assertTrue(
            validated.none {
                (it.location as? Location.FileRef)?.path == "src/DoesNotExist.kt"
            },
            "Hallucinated finding at src/DoesNotExist.kt should be dropped",
        )
        assertTrue(
            validated.any { it.location == null },
            "MR-level finding (null location) should always pass",
        )
    }

    @Test
    fun `FindingLocationValidator drops finding on an unchanged line in strict mode`() {
        val wrongLineFinding = Finding(
            severity = Severity.WARNING,
            message = "Style violation",
            rule = "ai-review-comments",
            location = Location.FileRef(path = "src/Real.kt", line = 99),
        )

        val validated = FindingLocationValidator.validate(
            findings = listOf(wrongLineFinding),
            mr = testMr,
            ruleId = "ai-review-comments",
            strict = true,
        )

        assertTrue(validated.isEmpty(), "Finding on line 99, which the MR did not add, should be dropped")
    }

    @Test
    fun `FindingLocationValidator keeps wrong-line finding in non-strict mode`() {
        val wrongLineFinding = Finding(
            severity = Severity.WARNING,
            message = "Style violation",
            rule = "ai-review-comments",
            location = Location.FileRef(path = "src/Real.kt", line = 99),
        )

        val validated = FindingLocationValidator.validate(
            findings = listOf(wrongLineFinding),
            mr = testMr,
            ruleId = "ai-review-comments",
            strict = false,
        )

        assertEquals(1, validated.size, "Non-strict mode should keep the finding")
    }

    @Test
    fun `ConventionContextEnricher produces block with project conventions heading`() =
        runTest {
            val enricher = ConventionContextEnricher().also { it.useBaseFiles(mapOf(".editorconfig" to "[*.kt]\nindent_size = 4\n")) }
            val ctx = testPluginContext(workingDirectory = "/fake/project")
            enricher.initialize(ctx)

            val block = enricher.provideBlock(
                mr = testMr,
                changes = listOf(realFileChange),
                config = JsonNull,
            )

            assertNotNull(block, "ConventionContextEnricher should produce a block")
            assertEquals(
                ConventionContextEnricher.BLOCK_HEADING,
                block.heading,
                "Block heading should be '#### Project conventions'",
            )
            assertTrue(
                block.content.contains(".editorconfig"),
                "Block content should reference the convention file",
            )
            assertEquals(
                ConventionContextEnricher.BLOCK_PRIORITY,
                block.priority,
                "Block priority should match the enricher constant",
            )

            enricher.shutdown()
        }

    @Test
    fun `enricher registered in ModuleRegistry is discoverable via aiContextEnrichers`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val enricher = StubContextEnricher(
                blockToReturn = AiContextBlock(
                    heading = "#### Test context",
                    content = "test enricher output",
                    priority = 50,
                    source = "stub-enricher",
                ),
            )
            registry.register(enricher)

            val enrichers = registry.aiContextEnrichers()
            assertEquals(1, enrichers.size, "Registry should contain the stub enricher")
            assertEquals("stub-enricher", enrichers.first().id)

            val block = enrichers.first().provideBlock(testMr, testMr.changes, JsonNull)
            assertNotNull(block, "Enricher should return a block")
            assertEquals("#### Test context", block.heading)
        }

    @Test
    fun `combined scenario -- enricher block produced and validator filters findings`() =
        runTest {
            // Enricher produces a context block (simulating prompt assembly)
            val enricher = ConventionContextEnricher().also {
                it.useBaseFiles(mapOf(".editorconfig" to "[*.kt]\nindent_size = 4\nmax_line_length = 120\n"))
            }
            val ctx = testPluginContext(workingDirectory = "/fake/project")
            enricher.initialize(ctx)

            val block = enricher.provideBlock(testMr, testMr.changes, JsonNull)
            assertNotNull(block, "Enricher should produce a conventions block")
            assertEquals(
                ConventionContextEnricher.BLOCK_HEADING,
                block.heading,
                "Block heading must be '${ConventionContextEnricher.BLOCK_HEADING}'",
            )

            // Simulate AI response with mixed valid/hallucinated findings
            val aiFindings = listOf(
                Finding(
                    severity = Severity.ERROR,
                    message = "Missing null check on line 2",
                    rule = "ai-review-comments",
                    location = Location.FileRef(path = "src/Real.kt", line = 2),
                ),
                Finding(
                    severity = Severity.WARNING,
                    message = "Unused import in phantom file",
                    rule = "ai-review-comments",
                    location = Location.FileRef(
                        path = "src/DoesNotExist.kt",
                        line = 42,
                    ),
                ),
                Finding(
                    severity = Severity.INFO,
                    message = "Consider splitting this MR",
                    rule = "ai-review-comments",
                    location = null,
                ),
            )

            // Validator filters hallucinated findings
            val validated = FindingLocationValidator.validate(
                findings = aiFindings,
                mr = testMr,
                ruleId = "ai-review-comments",
                strict = true,
                diagnostics = ContextQualityDiagnostics(NoOpLogger),
            )

            assertEquals(
                2,
                validated.size,
                "Only valid + MR-level findings should survive validation",
            )
            assertTrue(
                validated.any {
                    it.message == "Missing null check on line 2"
                },
                "Valid finding should be preserved",
            )
            assertTrue(
                validated.any {
                    it.message == "Consider splitting this MR"
                },
                "MR-level finding should be preserved",
            )
            assertTrue(
                validated.none {
                    it.message == "Unused import in phantom file"
                },
                "Hallucinated finding should be dropped",
            )

            enricher.shutdown()
        }

    @Test
    fun `CommitRef findings always pass the validator`() {
        val commitFinding = Finding(
            severity = Severity.WARNING,
            message = "Commit message too short",
            rule = "ai-review-comments",
            location = Location.CommitRef(sha = "abc123"),
        )

        val validated = FindingLocationValidator.validate(
            findings = listOf(commitFinding),
            mr = testMr,
            ruleId = "ai-review-comments",
            strict = true,
        )

        assertEquals(1, validated.size, "CommitRef findings should always pass")
    }
}

// -- Test helpers --

/**
 * Stub [AiContextEnricherModule] that returns a pre-configured [AiContextBlock]
 * from [provideBlock]. Used to verify registry wiring without filesystem dependencies.
 */
private class StubContextEnricher(
    private val blockToReturn: AiContextBlock?,
) : AiContextEnricherModule {
    override val id: String = "stub-enricher"
    override val name: String = "Stub Enricher"
    override val version: String = "1.0.0"
    override val description: String = "Test stub for integration tests"
    override val dslName: String = "stubEnricher"
    override val enricherPriority: Int = 50
    override val defaultEnabled: Boolean = true

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun shutdown() = Unit

    override suspend fun enrich(
        mr: MergeRequest,
        context: AiContextEnricherContext,
    ): Map<String, String> = emptyMap()

    override suspend fun provideBlock(
        mr: MergeRequest,
        changes: List<FileChange>,
        config: JsonElement,
        budget: BlockBudget,
    ): AiContextBlock? = blockToReturn
}

/** Creates a minimal [PluginContext] for test use. */
private fun testPluginContext(
    workingDirectory: String = "/tmp",
    config: JsonElement = JsonObject(emptyMap()),
): PluginContext =
    object : PluginContext {
        override val pluginConfig: JsonElement = config
        override val hostVersion: String = "test"
        override val workingDirectory: String = workingDirectory
        override val logger: Logger = NoOpLogger

        override fun env(name: String): String? = null
    }
