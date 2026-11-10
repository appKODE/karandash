package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.conditions.DefaultPolicyEngine
import io.karandash.config.Config
import io.karandash.config.PolicyConfig
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.config.getRuleConfigElements
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Policy `modules:` / `override:` ids written in any naming convention must target the
 * configured (camelCase) rule key, and guest rules with kebab ids must find their config/meta.
 */
class ValidationEnginePolicyIdNormalizationTest {
    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "feat: x",
        description = null,
        author = User(username = "alice", name = "alice"),
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

    /** Emits one WARNING finding whose message carries the configured `max` value. */
    private class EchoRule(
        private val ruleId: String,
    ) : RuleModule {
        override val id: String = ruleId
        override val name: String = ruleId
        override val version: String = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(ruleId)

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            val max = configElement.jsonObject["max"]?.jsonPrimitive?.content ?: "unset"
            return listOf(Finding(rule = ruleId, message = "max=$max", severity = Severity.WARNING))
        }

        override suspend fun initialize(ctx: PluginContext): Boolean = true

        override suspend fun shutdown() = Unit
    }

    private suspend fun validate(
        ruleKey: String,
        moduleRuleId: String,
        policy: PolicyConfig? = null,
        meta: RuleExecutionMeta? = null,
    ): List<Finding> {
        val registry = ModuleRegistryImpl()
        registry.register(EchoRule(moduleRuleId))
        val config = Config(
            rules = RulesConfig(
                ruleConfigs = mapOf(ruleKey to JsonObject(mapOf("max" to JsonPrimitive(10)))),
                ruleMeta = meta?.let { mapOf(ruleKey to it) } ?: emptyMap(),
            ),
        )
        val engine = ValidationEngine(
            config = config,
            registry = registry,
            configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            policy = PolicyOptions(engine = policy?.let { DefaultPolicyEngine() }, policies = listOfNotNull(policy)),
        )
        return engine.validate(mr).findings
    }

    private val spellings = listOf("title-length", "title_length", "TitleLength", "titleLength")

    @Test
    fun `policy modules in any spelling disable the camelCase rule`() =
        runTest {
            for (spelling in spellings) {
                val policy = PolicyConfig(condition = MATCHES, modules = listOf(spelling), enabled = false)
                val findings = validate("titleLength", "titleLength", policy)
                assertTrue(findings.isEmpty(), "modules: [$spelling] should disable titleLength, got $findings")
            }
        }

    @Test
    fun `policy override keyed in any spelling merges into the camelCase rule`() =
        runTest {
            for (spelling in spellings) {
                val policy = PolicyConfig(
                    condition = MATCHES,
                    modules = listOf(spelling),
                    override = JsonObject(mapOf(spelling to JsonObject(mapOf("max" to JsonPrimitive(99))))),
                )
                val findings = validate("titleLength", "titleLength", policy)
                assertEquals(listOf("max=99"), findings.map { it.message }, "override via '$spelling'")
            }
        }

    @Test
    fun `unscoped policy override keyed in kebab-case merges into the camelCase rule`() =
        runTest {
            val policy = PolicyConfig(
                condition = MATCHES,
                override = JsonObject(mapOf("title-length" to JsonObject(mapOf("max" to JsonPrimitive(42))))),
            )
            val findings = validate("titleLength", "titleLength", policy)
            assertEquals(listOf("max=42"), findings.map { it.message })
        }

    @Test
    fun `policy severity in any spelling applies to the camelCase rule`() =
        runTest {
            for (spelling in spellings) {
                val policy = PolicyConfig(condition = MATCHES, modules = listOf(spelling), severity = "error")
                val findings = validate("titleLength", "titleLength", policy)
                assertEquals(listOf(Severity.ERROR), findings.map { it.severity }, "severity via '$spelling'")
            }
        }

    @Test
    fun `guest kebab rule runs when configured under its kebab or camelCase key`() =
        runTest {
            for (key in listOf("go-title-length", "goTitleLength")) {
                val findings = validate(key, "go-title-length")
                assertEquals(listOf("max=10"), findings.map { it.message }, "configured as '$key'")
            }
        }

    @Test
    fun `guest kebab rule honours meta and policies addressed by either spelling`() =
        runTest {
            val meta = RuleExecutionMeta(severity = "info")
            val withMeta = validate("goTitleLength", "go-title-length", meta = meta)
            assertEquals(listOf(Severity.INFO), withMeta.map { it.severity })

            val policy = PolicyConfig(condition = MATCHES, modules = listOf("go-title-length"), enabled = false)
            assertTrue(validate("goTitleLength", "go-title-length", policy).isEmpty())
        }

    /** the AI module contributes `code-suggestions` again, synthesized as the `codeSuggestions` rule key. */
    @Test
    fun `policy modules code-suggestions reaches the live code-suggestions rule`() =
        runTest {
            assertEquals(1, validate("codeSuggestions", "code-suggestions").size)
            for (spelling in listOf("code-suggestions", "codeSuggestions")) {
                val policy = PolicyConfig(condition = MATCHES, modules = listOf(spelling), enabled = false)
                assertTrue(validate("codeSuggestions", "code-suggestions", policy).isEmpty(), "modules: [$spelling]")
            }
        }
}

/** Matches the fixture MR (`feature/x`); the condition language has no `true` literal. */
private const val MATCHES = "branch_matches(\"^feature/\")"
