@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.finding.SkipCategory
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import io.karandash.api.platform.MergeRequestField
import io.karandash.conditions.BuiltinPredicates
import io.karandash.config.Config
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.config.WhenConfig
import io.karandash.logger.Logger
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationEngineRequiredFieldTest {
    private val mr =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = emptyList(),
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private val ciRule =
        object : RuleModule {
            override val id = "stub-ci"
            override val name = "Stub"
            override val version = "1.0"
            override val dslName = "stub-ci"

            override suspend fun contributedRules(): Set<String> = setOf("ci")

            override suspend fun initialize(context: PluginContext): Boolean = true

            override fun requiredMergeRequestFields(ruleId: String) = setOf(MergeRequestField.CI_STATUS)

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ) = listOf(Finding(Severity.ERROR, "ci failed", "ci"))
        }

    private val platformWithoutCi =
        object : PlatformServiceModule {
            override val id = "noci"
            override val name = "No CI"
            override val version = "1.0"

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun fetchMergeRequest(
                projectId: String,
                mrId: String,
            ): MergeRequest = mr

            override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = mr

            override fun unavailableMergeRequestFields() = setOf(MergeRequestField.CI_STATUS)

            override suspend fun addLabels(
                projectId: String,
                mrId: String,
                labels: List<String>,
            ) = Unit

            override suspend fun removeLabels(
                projectId: String,
                mrId: String,
                labels: List<String>,
            ) = Unit

            override suspend fun setDraft(
                projectId: String,
                mrId: String,
                draft: Boolean,
            ) = Unit

            override suspend fun updateTitle(
                projectId: String,
                mrId: String,
                title: String,
            ) = Unit

            override suspend fun updateDescription(
                projectId: String,
                mrId: String,
                description: String,
            ) = Unit

            override suspend fun approve(
                projectId: String,
                mrId: String,
            ) = Unit

            override suspend fun unapprove(
                projectId: String,
                mrId: String,
            ) = Unit

            override suspend fun postComment(
                projectId: String,
                mrId: String,
                body: String,
            ) = ""

            override suspend fun updateComment(
                projectId: String,
                mrId: String,
                commentId: String,
                body: String,
            ) = Unit

            override suspend fun findExistingComment(
                projectId: String,
                mrId: String,
                marker: String,
            ): String? = null

            override suspend fun postInlineComment(
                projectId: String,
                mrId: String,
                body: String,
                filePath: String,
                line: Int,
                diffRefs: DiffRefs,
                startLine: Int?,
            ) = ""
        }

    private val warnings = mutableListOf<String>()
    private val logger =
        object : Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) = Unit
        }

    private suspend fun engine(platform: PlatformServiceModule?): ValidationEngine {
        val registry = ModuleRegistryImpl()
        registry.register(ciRule)
        return ValidationEngine(
            config = Config(rules = RulesConfig(ruleConfigs = mapOf("ci" to JsonObject(emptyMap())))),
            registry = registry,
            configExtractor = { _, ruleId -> if (ruleId == "ci") listOf(JsonObject(emptyMap())) else emptyList() },
            logger = logger,
            platform = platform,
        )
    }

    @Test
    fun `rule needing a field the platform cannot fill is skipped with one warning`() =
        runTest {
            val result = engine(platformWithoutCi).validate(mr)

            assertTrue(result.findings.isEmpty())
            val skip = result.skippedRules.single()
            assertEquals("ci", skip.ruleId)
            assertEquals(SkipCategory.MISSING_MERGE_REQUEST_FIELD, skip.category)
            assertEquals(1, warnings.count { "ci" in it && "CI status" in it && "skipped" in it })
        }

    @Test
    fun `a rule declaring its fields per config is skipped only for a config that reads the missing field`() =
        runTest {
            val perConfigRule =
                object : RuleModule by ciRule {
                    override fun requiredMergeRequestFields(
                        ruleId: String,
                        config: JsonElement,
                    ) = if ("needsCi" in (config as JsonObject)) setOf(MergeRequestField.CI_STATUS) else emptySet()
                }

            suspend fun run(config: JsonObject): ValidationResult {
                val registry = ModuleRegistryImpl()
                registry.register(perConfigRule)
                return ValidationEngine(
                    config = Config(rules = RulesConfig(ruleConfigs = mapOf("ci" to config))),
                    registry = registry,
                    configExtractor = { _, _ -> listOf(config) },
                    logger = logger,
                    platform = platformWithoutCi,
                ).validate(mr)
            }

            assertEquals(1, run(JsonObject(emptyMap())).findings.size)
            assertEquals(1, run(JsonObject(mapOf("needsCi" to JsonPrimitive(true)))).skippedRules.size)
        }

    @Test
    fun `a when predicate reading a field the platform never fills warns once naming every rule`() {
        val predicates = BuiltinPredicates.createRegistry().all()
        val whenByRule = mapOf("a" to "!has_ci", "b" to "has_ci && is_draft", "c" to "is_draft")

        warnPredicatesOnMissingFields(whenByRule, predicates, platformWithoutCi, logger)
        warnPredicatesOnMissingFields(whenByRule, predicates, null, logger)

        val expected = "Predicate 'has_ci' in 'when' of rule 'a', 'b' sees no value: platform 'noci' doesn't provide CI status"
        assertEquals(listOf(expected), warnings)
    }

    @Test
    fun `when predicates are checked against the platform during validation`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(ciRule)
            val config =
                Config(
                    rules =
                        RulesConfig(
                            ruleConfigs = mapOf("ci" to JsonObject(emptyMap())),
                            ruleMeta = mapOf("ci" to RuleExecutionMeta(`when` = WhenConfig("!has_ci"))),
                        ),
                )
            ValidationEngine(config = config, registry = registry, logger = logger, platform = platformWithoutCi).validate(mr)

            assertEquals(1, warnings.count { it.startsWith("Predicate 'has_ci'") })
        }

    @Test
    fun `rule runs when no platform is known`() =
        runTest {
            val result = engine(null).validate(mr)

            assertEquals(1, result.findings.size)
            assertTrue(warnings.none { "skipped" in it })
        }
}
