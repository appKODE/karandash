package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.ContextTier
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.PromptPart
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.config.AiBudgetConfig
import io.karandash.plugins.ai.core.context.ConventionContextEnricher
import io.karandash.plugins.ai.core.context.DiffAnnotationsEnricher
import io.karandash.plugins.ai.core.context.SymbolDefinitionsEnricher
import io.karandash.plugins.ai.core.context.TicketContentEnricher
import io.karandash.plugins.ai.core.rules.AiContextQualityConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.BudgetConfig
import io.karandash.plugins.ai.core.rules.KnowledgeContextConfig
import io.karandash.plugins.ai.core.rules.PerRuleContextConfig
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tiers: T2 enricher blocks are capped through `knowledge-context` budgetFor, T0 blocks never are. */
class AiPromptAssemblerTiersTest {
    @Test
    fun `per-rule knowledge context budget caps a context block`() =
        runTest {
            val ci = RecordingEnricher("ci-context", listOf("ci:failed-jobs"))
            val cq = AiContextQualityConfig(
                ruleContext = mapOf(RULE to PerRuleContextConfig(knowledgeContext = budgets("ci:*" to 100))),
            )

            assemble(ci, cq)

            assertEquals(listOf(100), ci.budgets)
        }

    @Test
    fun `global knowledge context budget applies to every rule`() =
        runTest {
            val ci = RecordingEnricher("ci-context", listOf("ci:failed-jobs"))

            assemble(ci, AiContextQualityConfig(knowledgeContext = budgets("ci:failed-jobs" to 300)))

            assertEquals(listOf(300), ci.budgets)
        }

    @Test
    fun `max-chars 0 skips the block`() =
        runTest {
            val ci = RecordingEnricher("ci-context", listOf("ci:failed-jobs"))

            val extras = assemble(ci, AiContextQualityConfig(knowledgeContext = budgets("ci:*" to 0)))

            assertEquals(emptyList(), ci.budgets, "a zero budget never calls the enricher")
            assertTrue(extras.parts.none { it.name == "ci-context" })
        }

    @Test
    fun `ticket blocks are T0 with no budget and convention blocks are capped context`() =
        runTest {
            val tickets = RecordingEnricher(TicketContentEnricher.MODULE_ID, listOf("jira:tickets"))
            val conventions = RecordingEnricher(ConventionContextEnricher.MODULE_ID, emptyList())
            val cq = AiContextQualityConfig(knowledgeContext = budgets("jira:*" to 10, ConventionContextEnricher.MODULE_ID to 10))

            val extras = assemble(listOf(tickets, conventions), cq)

            assertEquals(listOf(Int.MAX_VALUE), tickets.budgets)
            assertEquals(listOf(10), conventions.budgets)
            val t0 = extras.t0.map { it.name }
            assertTrue(TicketContentEnricher.MODULE_ID in t0, "T0 was $t0")
            assertTrue(ConventionContextEnricher.MODULE_ID !in t0, "T0 was $t0")
        }

    @Test
    fun `enricher without enrichment ids keeps an unlimited budget unless named`() =
        runTest {
            val related = RecordingEnricher("related-file-context", emptyList())

            assemble(related, AiContextQualityConfig())

            assertEquals(listOf(Int.MAX_VALUE), related.budgets)
        }

    @Test
    fun `ai budget weights override a context block priority by its enricher id`() =
        runTest {
            val ci = RecordingEnricher("ci-context", listOf("ci:failed-jobs"))
            val cfg = AiPluginConfig(budget = AiBudgetConfig(weights = mapOf("ciContext" to 777)))

            val extras = assemble(listOf(ci), AiContextQualityConfig(), cfg)

            assertEquals(777, extras.parts.single { it.name == "ci-context" }.priority)
        }

    @Test
    fun `without weights a context block keeps its own priority`() =
        runTest {
            val extras = assemble(RecordingEnricher("ci-context", listOf("ci:failed-jobs")), AiContextQualityConfig())

            assertEquals(1, extras.parts.single { it.name == "ci-context" }.priority)
        }

    @Test
    fun `T0 over budget is a skip naming the item and its size`() {
        val extras = AssembledExtras(listOf(PromptPart("conventions", "c".repeat(400))), t0Names = setOf("conventions"))

        val tiered = extras.plan(systemPrompt = "s".repeat(40), metadata = "", maxInputTokens = 50, diffTokensWanted = 10)

        assertEquals("T0 over budget: conventions is 100 tokens, T0 is 110 of ai.context.budget 50", tiered.skipReason)
    }

    @Test
    fun `a remaining budget below the prompt cap shrinks the diff and counts the planned context`() {
        val extras = AssembledExtras(listOf(PromptPart("scopes", "s".repeat(40))), t0Names = setOf("scopes"))

        val tiered = extras.plan("", "", maxInputTokens = 1_000, diffTokensWanted = 500, remainingTokens = 100)

        assertNull(tiered.skipReason)
        assertEquals(90, tiered.diffTokens)
        assertEquals(10, tiered.contextTokens)
    }

    @Test
    fun `a remaining budget above the prompt cap leaves the plan unchanged`() {
        val extras = AssembledExtras(listOf(PromptPart("scopes", "s".repeat(40))), t0Names = setOf("scopes"))

        val tiered = extras.plan("", "", maxInputTokens = 1_000, diffTokensWanted = 500, remainingTokens = 50_000)

        assertEquals(500, tiered.diffTokens)
        assertEquals(10, tiered.contextTokens)
    }

    @Test
    fun `a T0 over the remaining budget leaves the skip to the budget gate`() {
        val extras = AssembledExtras(listOf(PromptPart("conventions", "c".repeat(400))), t0Names = setOf("conventions"))

        val tiered = extras.plan("", "", maxInputTokens = 1_000, diffTokensWanted = 10, remainingTokens = 50)

        assertNull(tiered.skipReason, "the budget, not the prompt cap, is short: the gate records budget exhausted")
        assertEquals(0, tiered.diffTokens)
        assertEquals(100, tiered.contextTokens)
        assertEquals(listOf("c".repeat(400)), tiered.contextBlocks.map { it.content })
    }

    @Test
    fun `dropped context is named and the kept parts render in order`() {
        val extras = AssembledExtras(
            listOf(
                PromptPart("scopes", "scope text"),
                PromptPart("crossFileContext", "x".repeat(400), priority = 1),
                PromptPart("ci-context", "ci text", priority = 9),
            ),
            t0Names = setOf("scopes"),
        )

        val tiered = extras.plan(systemPrompt = "", metadata = "", maxInputTokens = 20, diffTokensWanted = 10)

        assertNull(tiered.skipReason)
        assertEquals(listOf("crossFileContext"), tiered.contextDropped)
        assertEquals("scope text", tiered.combinedExtra)
        assertEquals(listOf("ci text"), tiered.contextBlocks.map { it.content })
        assertEquals(10, tiered.diffTokens)
    }

    @Test
    fun `context parts become shared context blocks and instructions stay in the extra instructions`() {
        val extras = AssembledExtras(
            listOf(
                PromptPart("scopes", "scope text"),
                PromptPart("ticket-content-enricher", "ticket text"),
                PromptPart("feedback", "feedback text"),
                PromptPart("crossFileContext", "cross text", priority = 1),
            ),
            t0Names = setOf("scopes", "ticket-content-enricher", "feedback"),
        )

        val tiered = extras.plan(systemPrompt = "", metadata = "", maxInputTokens = 1_000, diffTokensWanted = 10)

        assertEquals("scope text\n\nfeedback text", tiered.combinedExtra)
        assertEquals(listOf("ticket text", "cross text"), tiered.contextBlocks.map { it.content })
        assertEquals(listOf("ticket-content-enricher", "crossFileContext"), tiered.contextBlocks.map { it.source })
    }

    @Test
    fun `everything is kept when the cap is large`() {
        val extras = AssembledExtras(listOf(PromptPart("scopes", "a"), PromptPart("ci-context", "b")), t0Names = setOf("scopes"))

        val tiered = extras.plan(systemPrompt = "", metadata = "", maxInputTokens = 1_000, diffTokensWanted = 10)

        assertEquals(emptyList(), tiered.contextDropped)
        assertEquals("a", tiered.combinedExtra)
        assertEquals(listOf("b"), tiered.contextBlocks.map { it.content })
        assertTrue(tiered.skipReason == null)
    }

    @Test
    fun `a cross-file context over its cap is cut and named in context dropped`() =
        runTest {
            val extras = assemble(emptyList(), AiContextQualityConfig(), AiPluginConfig(crossFileContextMaxChars = 50), signatureMr(40))

            val part = extras.parts.single { it.name == "crossFileContext" }
            assertEquals("Cross-file context:\n".length + 50, part.text.length)
            val dropped = extras.plan(systemPrompt = "", metadata = "", maxInputTokens = 100_000, diffTokensWanted = 10).contextDropped
            assertTrue(dropped.single().startsWith("crossFileContext (cut to 50 of "), dropped.toString())
        }

    @Test
    fun `a cross-file context within its cap is sent whole and not named`() =
        runTest {
            val extras = assemble(emptyList(), AiContextQualityConfig(), AiPluginConfig(), signatureMr(2))

            assertTrue("fun f2()" in extras.parts.single { it.name == "crossFileContext" }.text)
            assertEquals(emptyList(), extras.plan("", "", maxInputTokens = 100_000, diffTokensWanted = 10).contextDropped)
        }

    @Test
    fun `an enricher block cut to its budget is named in context dropped`() =
        runTest {
            val ci = RecordingEnricher("ci-context", listOf("ci:failed-jobs"), cutFromChars = 900)

            val extras = assemble(ci, AiContextQualityConfig(knowledgeContext = budgets("ci:*" to 100)))

            val dropped = extras.plan("", "", maxInputTokens = 100_000, diffTokensWanted = 10).contextDropped
            assertEquals(listOf("ci-context (cut to 100 of 900 chars)"), dropped)
        }

    @Test
    fun `an unbudgeted block that left files out names the length it kept`() =
        runTest {
            val conventions = RecordingEnricher(ConventionContextEnricher.MODULE_ID, emptyList(), cutFromChars = 900)

            val extras = assemble(conventions, AiContextQualityConfig())

            val dropped = extras.plan("", "", maxInputTokens = 100_000, diffTokensWanted = 10).contextDropped
            assertEquals(listOf("convention-context (cut to 26 of 900 chars)"), dropped)
        }

    @Test
    fun `an enricher block within its budget is not named`() =
        runTest {
            val ci = RecordingEnricher("ci-context", listOf("ci:failed-jobs"))

            val extras = assemble(ci, AiContextQualityConfig(knowledgeContext = budgets("ci:*" to 100)))

            assertEquals(emptyList(), extras.plan("", "", maxInputTokens = 100_000, diffTokensWanted = 10).contextDropped)
        }

    @Test
    fun `a cut part the budget then drops whole is named once`() {
        val extras = AssembledExtras(
            listOf(PromptPart("crossFileContext", "x".repeat(400), priority = 1)),
            t0Names = emptySet(),
            cut = mapOf("crossFileContext" to "crossFileContext (cut to 400 of 900 chars)"),
        )

        assertEquals(listOf("crossFileContext"), extras.plan("", "", maxInputTokens = 20, diffTokensWanted = 10).contextDropped)
    }

    private fun signatureMr(functions: Int) =
        createTestMergeRequest(
            changes = listOf(
                FileChange(
                    path = "src/A.kt",
                    changeType = ChangeType.MODIFIED,
                    diffContent = (1..functions).joinToString("\n") { "+fun f$it() = Unit" },
                ),
            ),
        )

    private suspend fun assemble(
        enricher: RecordingEnricher,
        cq: AiContextQualityConfig,
    ): AssembledExtras = assemble(listOf(enricher), cq)

    private suspend fun assemble(
        enrichers: List<RecordingEnricher>,
        cq: AiContextQualityConfig,
        pluginCfg: AiPluginConfig = AiPluginConfig(),
        mr: MergeRequest = createTestMergeRequest(),
    ): AssembledExtras =
        AiPromptAssembler(registryWith(enrichers), NoOpLogger).assembleExtras(
            ruleId = RULE,
            mr = mr,
            changes = mr.changes,
            pluginCfg = pluginCfg,
            aiRuleConfig = AiRuleConfig(contextQuality = cq.copy(findingValidator = cq.findingValidator.copy(enabled = false))),
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = FeedbackStore(),
            // Each enricher's enrichment ids delivered, so the data gate lets every one run.
            enricherConfigJson = JsonObject(enrichers.flatMap { it.handledEnrichmentIds() }.associateWith { JsonObject(emptyMap()) }),
            needsScopeDetection = { false },
        )

    private fun budgets(vararg entries: Pair<String, Int>) =
        KnowledgeContextConfig(budgets = entries.associate { (id, chars) -> id to BudgetConfig(chars) })

    private fun registryWith(enrichers: List<AiContextEnricherModule>): ModuleRegistry =
        object : ModuleRegistry by buildTestRegistry() {
            override fun aiContextEnrichers(): List<AiContextEnricherModule> = enrichers
        }

    private class RecordingEnricher(
        override val id: String,
        private val ids: List<String>,
        private val cutFromChars: Int? = null,
    ) : AiContextEnricherModule {
        val budgets = mutableListOf<Int>()
        override val name: String = id
        override val version: String = "1"
        override val description: String = id

        override fun handledEnrichmentIds(): List<String> = ids

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun enrich(
            mr: MergeRequest,
            context: AiContextEnricherContext,
        ): Map<String, String> = emptyMap()

        override suspend fun provideBlock(
            mr: MergeRequest,
            changes: List<FileChange>,
            config: JsonElement,
            budget: BlockBudget,
        ): AiContextBlock {
            budgets += budget.maxChars
            return AiContextBlock(heading = "#### $id", content = "$id content", priority = 1, source = id, cutFromChars = cutFromChars)
        }
    }

    @Test
    fun `diff notes fill the deleted code tier and symbol definitions their own tier`() {
        assertEquals(ContextTier.DELETED_CODE, AssembledExtras.tierOf(DiffAnnotationsEnricher.MODULE_ID))
        assertEquals(ContextTier.SYMBOL_DEFINITIONS, AssembledExtras.tierOf(SymbolDefinitionsEnricher.MODULE_ID))
    }

    private companion object {
        const val RULE = "review-comments"
    }
}
