package io.karandash.cli.pipeline

import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.config.EnrichmentConfig
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.module.AbstractRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnrichmentStageTest {
    private val stubMr = MergeRequest(
        id = "42",
        iid = 42,
        title = "wip: something",
        description = null,
        sourceBranch = "feature/x",
        targetBranch = "main",
        author = User(username = "dev", name = "Dev", email = "dev@example.com"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = listOf(
            Commit(
                sha = "abc",
                message = "add something",
                author = CommitAuthor(name = "Dev", email = "dev@example.com"),
                timestamp = "2024-01-01T00:00:00Z",
            )
        ),
        changes = listOf(
            FileChange(
                path = "src/Main.kt",
                changeType = ChangeType.ADDED,
                additions = 10,
                deletions = 0,
            )
        ),
    )

    private val stubMrWithDescription = stubMr.copy(description = "Existing description.")

    private class RecordingMutator(
        private val fetchedMr: MergeRequest? = null,
        override val id: String = "stub-mutator",
    ) : PlatformServiceModule {
        override val name = "Stub Mutator"
        override val version = "1.0"
        override val type = ModuleType.PLATFORM_SERVICE

        val titlesSet = mutableListOf<String>()
        val descriptionsSet = mutableListOf<String>()

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun shutdown() {}

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) {}

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) {
            titlesSet += title
        }

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) {
            descriptionsSet += description
        }

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = checkNotNull(fetchedMr) { "fetchMergeRequest not configured on RecordingMutator" }

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest =
            checkNotNull(fetchedMr) { "fetchMergeRequestByUrl not configured on RecordingMutator" }

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String
        ) {}

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: io.karandash.models.DiffRefs,
            startLine: Int?,
        ) = ""
    }

    /**
     * A fake RuleModule that contributes the ai-generate-description rule and returns
     * configurable findings, allowing EnrichmentStage to be tested without a real AI provider.
     */
    private class FakeDescriptionGeneratorModule(
        private val findings: List<Finding> = listOf(
            Finding(
                severity = Severity.INFO,
                message = "AI suggestion",
                rule = EnrichmentStage.RULE_GENERATE_DESCRIPTION,
                metadata = mapOf(
                    "suggestedTitle" to "feat: improved title",
                    "suggestedDescription" to "Better description.",
                ),
            )
        ),
    ) : AbstractRuleModule() {
        var receivedConfig: JsonElement? = null

        override val id = "fake-description-generator"
        override val name = "Fake Description Generator"
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(EnrichmentStage.RULE_GENERATE_DESCRIPTION)

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            receivedConfig = configElement
            return findings
        }
    }

    private fun buildStage(
        config: EnrichmentConfig,
        registry: ModuleRegistryImpl,
        provider: PlatformServiceModule? = null,
        dryRun: Boolean = false,
        runtimeModuleInitializer: suspend (Module) -> Unit = {},
        platformHint: String? = null,
    ): EnrichmentStage =
        EnrichmentStage(
            config = config,
            registry = registry,
            provider = provider,
            projectId = "group/repo",
            mrId = "42",
            dryRun = dryRun,
            logger = io.karandash.sdk.NoOpLogger,
            runtimeModuleInitializer = runtimeModuleInitializer,
            platformHint = platformHint,
        )

    @Test
    fun `enrichment disabled when both flags are false`() =
        runTest {
            val config = EnrichmentConfig(generateDescription = false, generateTitle = false)
            val stage = buildStage(config, ModuleRegistryImpl())
            val result = stage.enrich(stubMr)
            assertSame(stubMr, result)
        }

    @Test
    fun `enrichment skipped when description exists and guard enabled`() =
        runTest {
            val config = EnrichmentConfig(
                generateDescription = true,
                generateTitle = true,
                skipIfDescriptionExists = true,
            )
            val stage = buildStage(config, ModuleRegistryImpl())
            val result = stage.enrich(stubMrWithDescription)
            assertSame(stubMrWithDescription, result)
        }

    @Test
    fun `enrichment runs when description exists but guard disabled`() =
        runTest {
            val applied =
                listOf(false, true).associateWith { guard ->
                    val registry = ModuleRegistryImpl()
                    registry.register(FakeDescriptionGeneratorModule())
                    val mutator = RecordingMutator(fetchedMr = stubMrWithDescription)
                    registry.register(mutator)
                    val config = EnrichmentConfig(
                        generateDescription = true,
                        generateTitle = true,
                        skipIfDescriptionExists = guard,
                    )
                    buildStage(config, registry, provider = mutator, dryRun = false).enrich(stubMrWithDescription)
                    mutator.titlesSet.size + mutator.descriptionsSet.size
                }
            assertEquals(2, applied[false], "guard off applies title and description")
            assertEquals(0, applied[true], "guard on applies nothing")
        }

    @Test
    fun `enrichment skipped gracefully when no generator module registered`() =
        runTest {
            val config = EnrichmentConfig(generateDescription = true, generateTitle = true)
            val stage = buildStage(config, ModuleRegistryImpl())
            val result = stage.enrich(stubMr)
            assertSame(stubMr, result)
        }

    @Test
    fun `dry-run previews suggestions without calling mutator`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeDescriptionGeneratorModule())

            val mutator = RecordingMutator()
            registry.register(mutator)

            val config = EnrichmentConfig(generateDescription = true, generateTitle = true)
            val stage = buildStage(config, registry, provider = null, dryRun = true)
            val result = stage.enrich(stubMr)

            assertSame(stubMr, result)
            assertEquals(0, mutator.titlesSet.size)
            assertEquals(0, mutator.descriptionsSet.size)
        }

    @Test
    fun `enrichment applies title and description via mutator and re-fetches`() =
        runTest {
            val enrichedMr = stubMr.copy(title = "feat: improved title", description = "Better description.")
            val registry = ModuleRegistryImpl()
            registry.register(FakeDescriptionGeneratorModule())

            val mutator = RecordingMutator(fetchedMr = enrichedMr)
            registry.register(mutator)

            val config = EnrichmentConfig(generateDescription = true, generateTitle = true)
            val stage = buildStage(config, registry, provider = mutator, dryRun = false)
            val result = stage.enrich(stubMr)

            assertEquals("feat: improved title", mutator.titlesSet.firstOrNull())
            assertEquals("Better description.", mutator.descriptionsSet.firstOrNull())
            assertEquals(enrichedMr, result)
        }

    @Test
    fun `enrichment only applies title when generateDescription is false`() =
        runTest {
            val enrichedMr = stubMr.copy(title = "feat: improved title")
            val registry = ModuleRegistryImpl()
            registry.register(FakeDescriptionGeneratorModule())

            val mutator = RecordingMutator(fetchedMr = enrichedMr)
            registry.register(mutator)

            val config = EnrichmentConfig(generateDescription = false, generateTitle = true)
            val stage = buildStage(config, registry, provider = mutator, dryRun = false)
            stage.enrich(stubMr)

            assertEquals(1, mutator.titlesSet.size)
            assertEquals(0, mutator.descriptionsSet.size)
        }

    @Test
    fun `enrichment only applies description when generateTitle is false`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeDescriptionGeneratorModule())
            val mutator = RecordingMutator(fetchedMr = stubMr.copy(description = "Better description."))
            registry.register(mutator)

            val config = EnrichmentConfig(generateDescription = true, generateTitle = false)
            buildStage(config, registry, provider = mutator, dryRun = false).enrich(stubMr)

            assertEquals(0, mutator.titlesSet.size)
            assertEquals(1, mutator.descriptionsSet.size)
        }

    @Test
    fun `without a live provider the mutator is the platform service the CI platform names`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeDescriptionGeneratorModule())
            val first = RecordingMutator(id = "gitlab")
            val second = RecordingMutator(id = "github")
            registry.register(first)
            registry.register(second)
            val config = EnrichmentConfig(generateDescription = true, generateTitle = true)

            buildStage(config, registry, provider = null, platformHint = "github").enrich(stubMr)

            assertEquals(1, second.descriptionsSet.size)
            assertTrue(first.descriptionsSet.isEmpty())
        }

    @Test
    fun `enrichment initializes selected mutator before applying suggestions`() =
        runTest {
            val enrichedMr = stubMr.copy(title = "feat: improved title", description = "Better description.")
            val registry = ModuleRegistryImpl()
            registry.register(FakeDescriptionGeneratorModule())

            val mutator = RecordingMutator(fetchedMr = enrichedMr)
            registry.register(mutator)

            val initializedMutators = mutableListOf<String>()
            val receivedConfigs = mutableListOf<Map<String, Any?>>()
            val config = EnrichmentConfig(generateDescription = true, generateTitle = true)
            val configuredMutators = mapOf(mutator.configKey to mapOf<String, Any?>("token" to "abc"))
            val stage = buildStage(
                config = config,
                registry = registry,
                provider = mutator,
                dryRun = false,
                runtimeModuleInitializer = { module ->
                    initializedMutators += module.id
                    receivedConfigs += configuredMutators[module.configKey].orEmpty()
                }
            )

            stage.enrich(stubMr)

            assertEquals(listOf(mutator.id), initializedMutators)
            assertEquals(listOf(mapOf<String, Any?>("token" to "abc")), receivedConfigs)
            assertEquals(1, mutator.titlesSet.size)
            assertEquals(1, mutator.descriptionsSet.size)
        }

    @Test
    fun `an unconfigured maxTokens is not passed so the rule keeps its own limit`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val generator = FakeDescriptionGeneratorModule()
            registry.register(generator)

            buildStage(EnrichmentConfig(generateDescription = true), registry, dryRun = true).enrich(stubMr)

            assertEquals(emptySet(), checkNotNull(generator.receivedConfig).jsonObject.keys)
        }

    @Test
    fun `a configured maxTokens and model reach the rule`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val generator = FakeDescriptionGeneratorModule()
            registry.register(generator)

            buildStage(EnrichmentConfig(generateDescription = true, model = "m", maxTokens = 700), registry, dryRun = true).enrich(stubMr)

            val received = checkNotNull(generator.receivedConfig).jsonObject
            assertEquals("700", received.getValue("maxTokens").jsonPrimitive.content)
            assertEquals("m", received.getValue("model").jsonPrimitive.content)
        }
}
