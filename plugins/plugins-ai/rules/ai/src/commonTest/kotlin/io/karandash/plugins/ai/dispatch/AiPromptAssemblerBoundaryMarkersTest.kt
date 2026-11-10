package io.karandash.plugins.ai.dispatch

import io.karandash.api.ModuleRegistry
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.api.context.EnrichmentContext
import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.context.CiContextEnricher
import io.karandash.plugins.ai.core.context.FullFileContextEnricher
import io.karandash.plugins.ai.core.context.TicketContentEnricher
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.mergedEnricherConfigJson
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Verifies that AI context blocks from enrichers are wrapped in
 * `<untrusted-content>` boundary markers (prompt-injection defence).
 */
class AiPromptAssemblerBoundaryMarkersTest {
    private val noopLogger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private fun makeMr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Add feature",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = User("1", "dev", "Dev"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = listOf(Commit("sha1", "init", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z")),
            changes = listOf(
                FileChange(path = "src/Foo.kt", changeType = ChangeType.ADDED, additions = 1, diffContent = "+foo"),
            ),
        )

    private fun registryWithEnricher(
        block: AiContextBlock?,
        failure: Exception? = null,
    ): ModuleRegistry {
        val enricher = object : AiContextEnricherModule {
            override val id: String = "test-enricher"
            override val name: String = "Test Enricher"
            override val version: String = "1.0"
            override val description: String = "test"

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun enrich(
                mr: MergeRequest,
                ctx: AiContextEnricherContext,
            ): Map<String, String> = emptyMap()

            override suspend fun provideBlock(
                mr: MergeRequest,
                changes: List<FileChange>,
                config: JsonElement,
                budget: BlockBudget,
            ): AiContextBlock? = if (failure != null) throw failure else block
        }
        val base = buildTestRegistry()
        return object : ModuleRegistry by base {
            override fun aiContextEnrichers(): List<AiContextEnricherModule> = listOf(enricher)
        }
    }

    @Test
    fun `provider block content is wrapped in untrusted-content boundary markers`() =
        runTest {
            val sensitiveContent = "Ticket summary: fix login bug\nAC: user can log in"
            val block = AiContextBlock(
                heading = "#### Linked tickets",
                content = sensitiveContent,
                priority = 300,
                source = "jira:tickets",
            )
            val registry = registryWithEnricher(block)
            val assembler = AiPromptAssembler(registry, noopLogger)

            val result = assembler.buildExtraInstructionsWithContext(
                ruleId = "review-comments",
                mr = makeMr(),
                changes = makeMr().changes,
                pluginCfg = AiPluginConfig(),
                aiRuleConfig = AiRuleConfig(),
                strictness = ReviewStrictness.STANDARD,
                feedbackStore = FeedbackStore(),
                enricherConfigJson = JsonNull,
                needsScopeDetection = { false },
            )

            assertNotNull(result, "Expected non-null assembled instructions")
            assertContains(result, "<untrusted-content source=\"jira:tickets\">")
            assertContains(result, "</untrusted-content>")
            assertContains(result, sensitiveContent)
        }

    @Test
    fun `block source attribute matches enrichment id in boundary marker`() =
        runTest {
            val block = AiContextBlock(
                heading = "#### CI failed jobs",
                content = "Job 'test' failed: AssertionError in FooTest",
                priority = 220,
                source = "ci:failed-jobs",
            )
            val registry = registryWithEnricher(block)
            val assembler = AiPromptAssembler(registry, noopLogger)

            val result = assembler.buildExtraInstructionsWithContext(
                ruleId = "risk-scoring",
                mr = makeMr(),
                changes = makeMr().changes,
                pluginCfg = AiPluginConfig(),
                aiRuleConfig = AiRuleConfig(),
                strictness = ReviewStrictness.STANDARD,
                feedbackStore = FeedbackStore(),
                enricherConfigJson = JsonNull,
                needsScopeDetection = { false },
            )

            assertNotNull(result)
            assertContains(result, "<untrusted-content source=\"ci:failed-jobs\">")
        }

    @Test
    fun `failing provideBlock is logged at warn and does not fail assembly`() =
        runTest {
            val warnings = mutableListOf<String>()
            val logger = object : Logger by noopLogger {
                override fun warn(message: String) {
                    warnings += message
                }
            }
            val assembler = AiPromptAssembler(registryWithEnricher(null, IllegalStateException("boom")), logger)

            assembler.buildExtraInstructionsWithContext(
                ruleId = "review-comments",
                mr = makeMr(),
                changes = makeMr().changes,
                pluginCfg = AiPluginConfig(),
                aiRuleConfig = AiRuleConfig(),
                strictness = ReviewStrictness.STANDARD,
                feedbackStore = FeedbackStore(),
                enricherConfigJson = JsonNull,
                needsScopeDetection = { false },
            )

            assertEquals(1, warnings.count { "test-enricher provideBlock failed: boom" in it }, warnings.toString())
        }

    @Test
    fun `null provider block does not produce untrusted-content marker`() =
        runTest {
            val registry = registryWithEnricher(null)
            val assembler = AiPromptAssembler(registry, noopLogger)

            val result = assembler.buildExtraInstructionsWithContext(
                ruleId = "review-comments",
                mr = makeMr(),
                changes = makeMr().changes,
                pluginCfg = AiPluginConfig(),
                aiRuleConfig = AiRuleConfig(),
                strictness = ReviewStrictness.STANDARD,
                feedbackStore = FeedbackStore(),
                enricherConfigJson = JsonNull,
                needsScopeDetection = { false },
            )

            if (result != null) {
                assertFalse(
                    result.contains("<untrusted-content"),
                    "Null provider block should not produce untrusted-content marker",
                )
            }
        }

    @Test
    fun `real enrichment JSON reaches TicketContentEnricher and appears in assembled prompt`() =
        runTest {
            val base = buildTestRegistry()
            val registry = object : ModuleRegistry by base {
                override fun aiContextEnrichers(): List<AiContextEnricherModule> = listOf(TicketContentEnricher())
            }
            val assembler = AiPromptAssembler(registry, noopLogger)

            val enricherConfigJson = JsonObject(
                mapOf(
                    EnrichmentKeys.JIRA_TICKETS to JsonObject(
                        mapOf(
                            "PROJ-123" to JsonObject(
                                mapOf(
                                    "summary" to JsonPrimitive("Fix login screen crash"),
                                    "description" to JsonPrimitive("Crash on empty password field"),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val result = assembler.buildExtraInstructionsWithContext(
                ruleId = "review-comments",
                mr = makeMr(),
                changes = makeMr().changes,
                pluginCfg = AiPluginConfig(),
                aiRuleConfig = AiRuleConfig(),
                strictness = ReviewStrictness.STANDARD,
                feedbackStore = FeedbackStore(),
                enricherConfigJson = enricherConfigJson,
                needsScopeDetection = { false },
            )

            assertNotNull(result, "Expected non-null assembled instructions")
            assertContains(result, "#### Linked tickets")
            assertContains(result, "PROJ-123")
            assertContains(result, "Fix login screen crash")
            assertEquals(1, result.split("<untrusted-content source=").size - 1, "one boundary per block: $result")
            assertEquals(1, result.split("</untrusted-content>").size - 1, "one closing tag per block: $result")
        }

    /**
     * Mirrors a real `rule-enrichment: { review-comments: { enrich: ["gitlab:*", "ci:*"] } }`
     * config binding: multiple enrichment sources present at once, filtered down via the
     * same prefix-matching [EnrichmentContext.filter] the pipeline uses for `"prefix:*"`
     * patterns, then fanned out to multiple concurrently-running enrichers in one prompt.
     */
    @Test
    fun `multiple enrichment sources filtered by config-style prefixes all surface together`() =
        runTest {
            val fullContext = EnrichmentContext(
                data = mapOf(
                    // Requested via "gitlab:*" -- must appear.
                    EnrichmentKeys.GITLAB_ISSUES to JsonObject(
                        mapOf(
                            "7" to JsonObject(
                                mapOf(
                                    "title" to JsonPrimitive("Gitlab issue title should appear"),
                                    "description" to JsonPrimitive("desc"),
                                ),
                            ),
                        ),
                    ),
                    // Requested via "ci:*" -- must appear.
                    EnrichmentKeys.CI_FAILED_JOBS to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "name" to JsonPrimitive("unit-test"),
                                    "errorSnippet" to JsonPrimitive("AssertionError boom"),
                                ),
                            ),
                        ),
                    ),
                    EnrichmentKeys.CI_COVERAGE_GAPS to JsonObject(
                        mapOf(
                            "src/Auth.kt" to JsonArray(
                                listOf(JsonObject(mapOf("startLine" to JsonPrimitive(10), "endLine" to JsonPrimitive(20)))),
                            ),
                        ),
                    ),
                    // Not requested by this rule's binding -- must NOT appear.
                    EnrichmentKeys.JIRA_TICKETS to JsonObject(
                        mapOf("PROJ-1" to JsonObject(mapOf("summary" to JsonPrimitive("SHOULD NOT APPEAR jira ticket")))),
                    ),
                    EnrichmentKeys.CONFLUENCE_PAGES to JsonObject(
                        mapOf("123" to JsonObject(mapOf("title" to JsonPrimitive("SHOULD NOT APPEAR confluence page")))),
                    ),
                ),
            )

            // "gitlab:*" / "ci:*" as parsed by ModuleInitializer (pattern.removeSuffix("*")).
            val filtered = fullContext.filter(listOf("gitlab:", "ci:"))
            assertEquals(
                setOf(EnrichmentKeys.GITLAB_ISSUES, EnrichmentKeys.CI_FAILED_JOBS, EnrichmentKeys.CI_COVERAGE_GAPS),
                filtered.availableIds,
                "Multi-prefix filter should keep gitlab:*/ci:* entries and drop jira/confluence",
            )

            val enricherConfigJson = JsonObject(filtered.availableIds.associateWith { id -> checkNotNull(filtered.get(id)) })

            val base = buildTestRegistry()
            val registry = object : ModuleRegistry by base {
                override fun aiContextEnrichers(): List<AiContextEnricherModule> = listOf(TicketContentEnricher(), CiContextEnricher())
            }
            val assembler = AiPromptAssembler(registry, noopLogger)

            val result = assembler.buildExtraInstructionsWithContext(
                ruleId = "review-comments",
                mr = makeMr(),
                changes = makeMr().changes,
                pluginCfg = AiPluginConfig(),
                aiRuleConfig = AiRuleConfig(),
                strictness = ReviewStrictness.STANDARD,
                feedbackStore = FeedbackStore(),
                enricherConfigJson = enricherConfigJson,
                needsScopeDetection = { false },
            )

            assertNotNull(result, "Expected non-null assembled instructions")
            assertContains(result, "#### Linked tickets")
            assertContains(result, "Gitlab issue title should appear")
            assertContains(result, "#### CI signal")
            assertContains(result, "unit-test: AssertionError boom")
            assertContains(result, "src/Auth.kt: L10-L20")
            assertFalse(
                result.contains("SHOULD NOT APPEAR"),
                "jira/confluence data outside the configured prefixes must not leak into the prompt"
            )
        }

    /**
     * `config` is overloaded -- some enrichers read `ai:` plugin config
     * (e.g. `FullFileContextEnricher`'s `contextQuality.fullFileContext.enabled`), others
     * read enrichment provider data. [mergedEnricherConfigJson] must serve both from the
     * same object without either clobbering the other.
     */
    @Test
    fun `merged config preserves ai plugin config for FullFileContextEnricher alongside enrichment data`() =
        runTest {
            val aiPluginConfig = JsonObject(
                mapOf(
                    "contextQuality" to JsonObject(
                        mapOf(
                            "fullFileContext" to JsonObject(
                                mapOf(
                                    "enabled" to JsonPrimitive(true),
                                    "maxFileLines" to JsonPrimitive(500),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val enrichmentContext = EnrichmentContext(
                data = mapOf(EnrichmentKeys.JIRA_TICKETS to JsonObject(mapOf("PROJ-1" to JsonPrimitive("unrelated")))),
            )
            val merged = mergedEnricherConfigJson(aiPluginConfig, enrichmentContext)

            val base = buildTestRegistry()
            val registry = object : ModuleRegistry by base {
                override fun aiContextEnrichers(): List<AiContextEnricherModule> = listOf(FullFileContextEnricher())
            }
            val assembler = AiPromptAssembler(registry, noopLogger)

            val mrWithFullContent = makeMr().let { mr ->
                mr.copy(
                    changes = listOf(
                        FileChange(
                            path = "src/Foo.kt",
                            changeType = ChangeType.MODIFIED,
                            additions = 1,
                            diffContent = "+foo",
                            newContent = "class Foo {\n    fun bar() = 1\n}\n",
                        ),
                    ),
                )
            }

            val result = assembler.buildExtraInstructionsWithContext(
                ruleId = "review-comments",
                mr = mrWithFullContent,
                changes = mrWithFullContent.changes,
                pluginCfg = AiPluginConfig(),
                aiRuleConfig = AiRuleConfig(),
                strictness = ReviewStrictness.STANDARD,
                feedbackStore = FeedbackStore(),
                enricherConfigJson = merged,
                needsScopeDetection = { false },
            )

            assertNotNull(
                result,
                "FullFileContextEnricher must still see ai:contextQuality.fullFileContext.enabled=true " +
                    "after merging in enrichment data -- if this is null, the merge silently dropped the ai: config",
            )
            assertContains(result, "src/Foo.kt")
            assertContains(result, "fun bar()")
        }

    @Test
    fun `mergedEnricherConfigJson keeps both key sets without collision`() {
        val aiPluginConfig = JsonObject(mapOf("contextQuality" to JsonPrimitive("ai-config-value")))
        val enrichmentContext = EnrichmentContext(
            data = mapOf(EnrichmentKeys.JIRA_TICKETS to JsonPrimitive("enrichment-value")),
        )

        val merged = mergedEnricherConfigJson(aiPluginConfig, enrichmentContext) as JsonObject

        assertEquals(JsonPrimitive("ai-config-value"), merged["contextQuality"])
        assertEquals(JsonPrimitive("enrichment-value"), merged[EnrichmentKeys.JIRA_TICKETS])
    }

    @Test
    fun `mergedEnricherConfigJson returns JsonNull when both inputs are empty`() {
        val merged = mergedEnricherConfigJson(JsonNull, EnrichmentContext.EMPTY)
        assertEquals(JsonNull, merged)
    }
}
