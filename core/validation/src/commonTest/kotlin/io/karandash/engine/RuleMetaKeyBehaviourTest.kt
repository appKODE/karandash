package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.config.Config
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.config.getRuleConfigElements
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleMetaKeyBehaviourTest {
    private val mr =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "feat: add feature",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/x",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private class Stub(
        override val id: String,
        private val order: MutableList<String>,
        private val severity: Severity = Severity.WARNING,
        private val files: List<String?> = listOf(null),
    ) : RuleModule {
        override val name: String = "Stub $id"
        override val version: String = "1.0"
        override val dslName: String = id

        override suspend fun contributedRules(): Set<String> = setOf(id)

        override suspend fun initialize(ctx: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            order.add(id)
            return files.map { file ->
                Finding(rule = id, message = "found", severity = severity, location = file?.let { Location.FileRef(it) })
            }
        }
    }

    private suspend fun run(
        config: Config,
        vararg modules: RuleModule,
    ) = ValidationEngine(
        config = config,
        registry = ModuleRegistryImpl().also { reg -> modules.forEach { reg.register(it) } },
        configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
    ).validate(mr)

    private val empty = JsonObject(emptyMap())

    @Test
    fun `ruleConfigs decides which rules run`() =
        runTest {
            val order = mutableListOf<String>()
            val config = Config(rules = RulesConfig(ruleConfigs = mapOf("ruleA" to empty)))
            run(config, Stub("ruleA", order), Stub("ruleB", order))
            assertEquals(listOf("ruleA"), order)
        }

    @Test
    fun `rules block with no configured rule runs nothing`() =
        runTest {
            val order = mutableListOf<String>()
            val result = run(Config(rules = RulesConfig()), Stub("ruleA", order))
            assertEquals(emptyList(), order)
            assertEquals(emptyList(), result.findings)
        }

    @Test
    fun `priority runs lower numbers first`() =
        runTest {
            val order = mutableListOf<String>()
            val config =
                Config(
                    rules =
                        RulesConfig(
                            ruleConfigs = mapOf("late" to empty, "early" to empty),
                            ruleMeta =
                                mapOf(
                                    "late" to RuleExecutionMeta(priority = 50),
                                    "early" to RuleExecutionMeta(priority = 5),
                                ),
                        ),
                )
            run(config, Stub("late", order), Stub("early", order))
            assertEquals(listOf("early", "late"), order)
        }

    @Test
    fun `severity forces the severity of every finding`() =
        runTest {
            val order = mutableListOf<String>()
            val config =
                Config(
                    rules =
                        RulesConfig(
                            ruleConfigs = mapOf("ruleA" to empty),
                            ruleMeta = mapOf("ruleA" to RuleExecutionMeta(severity = "error")),
                        ),
                )
            val result = run(config, Stub("ruleA", order, Severity.WARNING))
            assertEquals(listOf(Severity.ERROR), result.findings.map { it.severity })
        }

    @Test
    fun `excludePaths drops findings located in matching files`() =
        runTest {
            val config =
                Config(
                    rules =
                        RulesConfig(
                            ruleConfigs = mapOf("ruleA" to empty),
                            ruleMeta = mapOf("ruleA" to RuleExecutionMeta(excludePaths = listOf("generated/**"))),
                        ),
                )
            val files = listOf("generated/Api.kt", "src/Main.kt", null)
            val result = run(config, Stub("ruleA", mutableListOf(), files = files))
            assertEquals(listOf("src/Main.kt", null), result.findings.map { (it.location as? Location.FileRef)?.path })
        }
}
