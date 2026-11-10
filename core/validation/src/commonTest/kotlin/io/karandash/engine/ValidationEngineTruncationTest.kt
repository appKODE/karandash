package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.config.Config
import io.karandash.config.LimitsConfig
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationEngineTruncationTest {
    private fun makeMR() =
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

    private fun stub(
        ruleId: String,
        result: List<Finding>,
    ): RuleModule =
        object : RuleModule {
            override val id = "stub-$ruleId"
            override val name = "Stub"
            override val version = "1.0"
            override val dslName = "stub-$ruleId"

            override suspend fun contributedRules(): Set<String> = setOf(ruleId)

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ) = result
        }

    /** A flood of warnings must not push an ERROR out of the truncated list. */
    @Test
    fun `error survives truncation after a warning flood`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val warnings = (1..300).map {
                Finding(
                    severity = Severity.WARNING,
                    message = "w",
                    rule = "flood",
                    location = io.karandash.models.finding.Location
                        .FileRef("f$it.kt")
                )
            }
            registry.register(stub("flood", warnings))
            registry.register(stub("late", listOf(Finding(severity = Severity.ERROR, message = "boom", rule = "late"))))
            val config =
                Config(
                    limits = LimitsConfig(maxFindings = 100),
                    rules =
                        RulesConfig(
                            ruleConfigs = mapOf("flood" to JsonObject(emptyMap()), "late" to JsonObject(emptyMap())),
                            ruleMeta =
                                mapOf(
                                    "flood" to RuleExecutionMeta(priority = 1),
                                    "late" to RuleExecutionMeta(priority = 10),
                                ),
                        ),
                )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })

            val result = engine.validate(makeMR())

            assertFalse(result.passed, "the ERROR must survive truncation")
            assertTrue(result.findings.any { it.rule == "late" })
            assertTrue(result.findings.any { it.message.contains("Findings limit reached") })
        }
}
