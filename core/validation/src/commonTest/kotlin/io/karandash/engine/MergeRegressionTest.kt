package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.config.Config
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.config.WhenConfig
import io.karandash.config.getRuleConfigElements
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Regressions for the one config merger. */
class MergeRegressionTest {
    private class RecordingRule : RuleModule {
        override val id = "body-rule"
        override val name = "Recording"
        override val version = "1.0"
        override val dslName = id
        var received: JsonElement? = null

        override suspend fun contributedRules(): Set<String> = setOf(id)

        override suspend fun initialize(ctx: PluginContext): Boolean = true

        override suspend fun shutdown() = Unit

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            received = configElement
            return emptyList()
        }
    }

    private val draftMr = MergeRequest(
        id = "1",
        iid = 1,
        title = "feat: add",
        description = null,
        author = User(username = "alice", name = "alice"),
        sourceBranch = "feature/x",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = true,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
        approvals = emptyList(),
        reviewers = emptyList(),
    )

    private suspend fun receivedConfig(
        base: String,
        override: String,
    ): JsonObject {
        val rule = RecordingRule()
        val registry = ModuleRegistryImpl().apply { register(rule) }
        val config = Config(
            rules = RulesConfig(
                ruleConfigs = mapOf("bodyRule" to Json.parseToJsonElement(base)),
                ruleMeta = mapOf(
                    "bodyRule" to RuleExecutionMeta(
                        `when` = WhenConfig(expression = "is_draft", override = Json.parseToJsonElement(override).jsonObject),
                    ),
                ),
            ),
        )
        ValidationEngine(
            config = config,
            registry = registry,
            configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
        ).validate(draftMr)
        return assertNotNull(rule.received).jsonObject
    }

    @Test
    fun `a null in a when override removes the key instead of storing a JSON null`() =
        runTest {
            val received = receivedConfig(base = """{"pattern":"^feat","maxLength":50}""", override = """{"pattern":null}""")

            assertEquals(Json.parseToJsonElement("""{"maxLength":50}"""), received)
        }

    /** the override keeps merging named list items by name through the shared merger */
    @Test
    fun `a when override still merges named list items by name`() =
        runTest {
            val received = receivedConfig(
                base = """{"sections":[{"name":"a","required":true},{"name":"b","required":true}]}""",
                override = """{"sections":[{"name":"b","required":false}]}""",
            )

            assertEquals(
                Json.parseToJsonElement("""{"sections":[{"name":"a","required":true},{"name":"b","required":false}]}"""),
                received,
            )
        }
}
