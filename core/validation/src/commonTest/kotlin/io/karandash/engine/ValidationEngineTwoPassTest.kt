package io.karandash.engine

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.context.ConditionContext
import io.karandash.api.context.ValidationContext
import io.karandash.api.module.FindingsAwareRuleModule
import io.karandash.api.module.RuleModule
import io.karandash.conditions.ConditionPredicate
import io.karandash.conditions.PredicateRegistry
import io.karandash.config.Config
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RuleMode
import io.karandash.config.RulesConfig
import io.karandash.config.WhenConfig
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationEngineTwoPassTest {
    private fun makeMR(
        draft: Boolean = false,
        labels: List<String> = emptyList(),
        changes: List<FileChange> = emptyList(),
    ): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "feat: add feature",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/test",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = draft,
            labels = labels,
            changes = changes,
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun finding(
        rule: String,
        severity: Severity = Severity.ERROR
    ) = Finding(severity = severity, message = "test", rule = rule)

    private fun stubRule(
        ruleId: String,
        result: List<Finding>,
        registerPredicatesFn: (PredicateRegistry<ConditionContext>) -> Unit = {},
    ): RuleModule =
        object : RuleModule {
            override val id = "stub-$ruleId"
            override val name = "Stub"
            override val version = "1.0"
            override val dslName = "stub-$ruleId"

            override suspend fun contributedRules() = setOf(ruleId)

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ) = result

            override fun registerPredicates(registry: PredicateRegistry<ConditionContext>) {
                registerPredicatesFn(registry)
            }
        }

    private fun findingsAwareRule(
        ruleId: String,
        normalResult: List<Finding>,
        awareResult: (ValidationContext) -> List<Finding>,
    ): FindingsAwareRuleModule =
        object : FindingsAwareRuleModule {
            override val id = "aware-$ruleId"
            override val name = "Aware"
            override val version = "1.0"
            override val dslName = "aware-$ruleId"

            override suspend fun contributedRules() = setOf(ruleId)

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun validate(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
            ) = normalResult

            override suspend fun validateWithFindings(
                ruleId: String,
                mr: MergeRequest,
                configElement: JsonElement,
                context: ValidationContext,
            ) = awareResult(context)
        }

    private fun buildEngine(
        registry: ModuleRegistry,
        ruleConfigs: Map<String, JsonElement>,
        ruleMeta: Map<String, RuleExecutionMeta> = emptyMap(),
    ): ValidationEngine {
        val config = Config(rules = RulesConfig(ruleConfigs = ruleConfigs, ruleMeta = ruleMeta))
        return ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })
    }

    // ── enabled_when tests ───────────────────────────────────────────────

    @Test
    fun `enabled_when skips rule when condition is false`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("myRule", listOf(finding("myRule", Severity.WARNING))))

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("myRule" to JsonObject(emptyMap())),
                ruleMeta = mapOf("myRule" to RuleExecutionMeta(`when` = WhenConfig(expression = "is_draft"))),
            )

            val result = engine.validate(makeMR(draft = false))
            assertEquals(0, result.findings.size)
            assertTrue(result.skippedRules.any { it.ruleId == "myRule" })
        }

    @Test
    fun `enabled_when runs rule when condition is true`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("myRule", listOf(finding("myRule", Severity.WARNING))))

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("myRule" to JsonObject(emptyMap())),
                ruleMeta = mapOf("myRule" to RuleExecutionMeta(`when` = WhenConfig(expression = "is_draft"))),
            )

            val result = engine.validate(makeMR(draft = true))
            assertEquals(1, result.findings.size)
            assertTrue(result.skippedRules.isEmpty())
        }

    @Test
    fun `enabled_when with label_exists`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(
                stubRule("labelRule", listOf(finding("labelRule", Severity.INFO))) { reg ->
                    reg.register(labelExistsPredicate())
                },
            )

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("labelRule" to JsonObject(emptyMap())),
                ruleMeta = mapOf("labelRule" to RuleExecutionMeta(`when` = WhenConfig(expression = "label_exists('security')"))),
            )

            val withLabel = engine.validate(makeMR(labels = listOf("security")))
            assertEquals(1, withLabel.findings.size)

            val withoutLabel = engine.validate(makeMR(labels = emptyList()))
            assertEquals(0, withoutLabel.findings.size)
        }

    @Test
    fun `malformed enabled_when skips rule gracefully`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("badRule", listOf(finding("badRule"))))

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("badRule" to JsonObject(emptyMap())),
                ruleMeta = mapOf("badRule" to RuleExecutionMeta(`when` = WhenConfig(expression = "unknown_func()"))),
            )

            val result = engine.validate(makeMR())
            assertEquals(listOf(ValidationEngine.ENGINE_RULE_ID), result.findings.map { it.rule })
        }

    // ── Two-pass tests ───────────────────────────────────────────────────

    @Test
    fun `SUPPLEMENT rules run in pass 2 and receive pass-1 findings`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("pass1Rule", listOf(finding("pass1Rule", Severity.WARNING))))

            var receivedContext: ValidationContext? = null
            registry.register(
                findingsAwareRule(
                    "pass2Rule",
                    normalResult = emptyList(),
                    awareResult = { ctx ->
                        receivedContext = ctx
                        listOf(finding("pass2Rule", Severity.INFO))
                    }
                )
            )

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("pass1Rule" to JsonObject(emptyMap()), "pass2Rule" to JsonObject(emptyMap())),
                ruleMeta = mapOf("pass2Rule" to RuleExecutionMeta(mode = RuleMode.SUPPLEMENT)),
            )

            val result = engine.validate(makeMR())
            assertEquals(2, result.findings.size)
            assertTrue(receivedContext != null)
            assertTrue(receivedContext!!.hasFindings("pass1Rule"))
            assertEquals(1, receivedContext!!.findingsFor("pass1Rule").size)
        }

    @Test
    fun `FALLBACK FindingsAwareRuleModule receives context`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("security", listOf(finding("security", Severity.ERROR))))

            var receivedContext: ValidationContext? = null
            registry.register(
                findingsAwareRule(
                    "aiSecurity",
                    normalResult = listOf(finding("aiSecurity", Severity.WARNING)),
                    awareResult = { ctx ->
                        receivedContext = ctx
                        if (ctx.hasFindings("security")) {
                            emptyList()
                        } else {
                            listOf(finding("aiSecurity", Severity.WARNING))
                        }
                    }
                )
            )

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("security" to JsonObject(emptyMap()), "aiSecurity" to JsonObject(emptyMap())),
                ruleMeta = mapOf("aiSecurity" to RuleExecutionMeta(mode = RuleMode.FALLBACK)),
            )

            val result = engine.validate(makeMR())
            // aiSecurity should see security's findings and return empty
            assertEquals(1, result.findings.size)
            assertEquals("security", result.findings[0].rule)
            assertTrue(receivedContext!!.hasFindings("security"))
        }

    @Test
    fun `NORMAL rules without mode run only in pass 1`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("ruleA", listOf(finding("ruleA", Severity.WARNING))))
            registry.register(stubRule("ruleB", listOf(finding("ruleB", Severity.INFO))))

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("ruleA" to JsonObject(emptyMap()), "ruleB" to JsonObject(emptyMap())),
                // No ruleMeta → both default to NORMAL
            )

            val result = engine.validate(makeMR())
            assertEquals(2, result.findings.size)
        }

    @Test
    fun `pass-2 enabled_when can use findings predicates`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("security", listOf(finding("security", Severity.ERROR))))
            registry.register(
                stubRule("aiFixSuggestions", listOf(finding("aiFixSuggestions", Severity.INFO))) { reg ->
                    reg.register(ruleHasFindingsPredicate())
                },
            )

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("security" to JsonObject(emptyMap()), "aiFixSuggestions" to JsonObject(emptyMap())),
                ruleMeta = mapOf(
                    "aiFixSuggestions" to RuleExecutionMeta(
                        mode = RuleMode.SUPPLEMENT,
                        `when` = WhenConfig(expression = "rule_has_findings('security')"),
                    ),
                ),
            )

            val result = engine.validate(makeMR())
            assertEquals(2, result.findings.size)
            assertTrue(result.findings.any { it.rule == "aiFixSuggestions" })
        }

    @Test
    fun `pass-2 enabled_when skips rule when findings predicate is false`() =
        runTest {
            val registry = ModuleRegistryImpl()
            // security produces NO findings
            registry.register(stubRule("security", emptyList()))
            registry.register(
                stubRule("aiFixSuggestions", listOf(finding("aiFixSuggestions", Severity.INFO))) { reg ->
                    reg.register(ruleHasFindingsPredicate())
                },
            )

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("security" to JsonObject(emptyMap()), "aiFixSuggestions" to JsonObject(emptyMap())),
                ruleMeta = mapOf(
                    "aiFixSuggestions" to RuleExecutionMeta(
                        mode = RuleMode.SUPPLEMENT,
                        `when` = WhenConfig(expression = "rule_has_findings('security')"),
                    ),
                ),
            )

            val result = engine.validate(makeMR())
            assertEquals(0, result.findings.size)
            assertTrue(result.skippedRules.any { it.ruleId == "aiFixSuggestions" })
        }

    @Test
    fun `non-FindingsAware module in pass 2 uses regular validate`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("pass1Rule", listOf(finding("pass1Rule", Severity.WARNING))))
            // Regular RuleModule (not FindingsAwareRuleModule) in pass 2
            registry.register(stubRule("pass2Regular", listOf(finding("pass2Regular", Severity.INFO))))

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("pass1Rule" to JsonObject(emptyMap()), "pass2Regular" to JsonObject(emptyMap())),
                ruleMeta = mapOf("pass2Regular" to RuleExecutionMeta(mode = RuleMode.SUPPLEMENT)),
            )

            val result = engine.validate(makeMR())
            assertEquals(2, result.findings.size)
        }

    // ── FALLBACK concern-area gating tests ──────────────────────────────

    @Test
    fun `FALLBACK rule skipped when concern area is covered by pass-1 findings`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("titleCheck", listOf(finding("titleCheck", Severity.WARNING))))
            registry.register(stubRule("aiFallback", listOf(finding("aiFallback", Severity.INFO))))

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("titleCheck" to JsonObject(emptyMap()), "aiFallback" to JsonObject(emptyMap())),
                ruleMeta = mapOf(
                    "titleCheck" to RuleExecutionMeta(concernArea = "title-quality"),
                    "aiFallback" to RuleExecutionMeta(mode = RuleMode.FALLBACK, concernArea = "title-quality"),
                ),
            )

            val result = engine.validate(makeMR())
            assertEquals(1, result.findings.size)
            assertEquals("titleCheck", result.findings[0].rule)
            assertTrue(result.skippedRules.any { it.ruleId == "aiFallback" })
        }

    @Test
    fun `FALLBACK rule executes when concern area is NOT covered by pass-1`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("titleCheck", emptyList()))
            registry.register(stubRule("aiFallback", listOf(finding("aiFallback", Severity.INFO))))

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("titleCheck" to JsonObject(emptyMap()), "aiFallback" to JsonObject(emptyMap())),
                ruleMeta = mapOf(
                    "titleCheck" to RuleExecutionMeta(concernArea = "title-quality"),
                    "aiFallback" to RuleExecutionMeta(mode = RuleMode.FALLBACK, concernArea = "title-quality"),
                ),
            )

            val result = engine.validate(makeMR())
            assertEquals(1, result.findings.size)
            assertEquals("aiFallback", result.findings[0].rule)
        }

    @Test
    fun `FALLBACK rule with null concern area always runs treated as SUPPLEMENT`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("titleCheck", listOf(finding("titleCheck", Severity.WARNING))))
            registry.register(stubRule("ungatedFallback", listOf(finding("ungatedFallback", Severity.INFO))))

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("titleCheck" to JsonObject(emptyMap()), "ungatedFallback" to JsonObject(emptyMap())),
                ruleMeta = mapOf(
                    "titleCheck" to RuleExecutionMeta(concernArea = "title-quality"),
                    "ungatedFallback" to RuleExecutionMeta(mode = RuleMode.FALLBACK),
                ),
            )

            val result = engine.validate(makeMR())
            assertEquals(2, result.findings.size)
        }

    @Test
    fun `multiple FALLBACK rules with independent concern areas are gated independently`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("titleCheck", listOf(finding("titleCheck", Severity.WARNING))))
            registry.register(stubRule("securityCheck", emptyList()))
            registry.register(stubRule("aiTitle", listOf(finding("aiTitle", Severity.INFO))))
            registry.register(stubRule("aiSecurity", listOf(finding("aiSecurity", Severity.INFO))))

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf(
                    "titleCheck" to JsonObject(emptyMap()),
                    "securityCheck" to JsonObject(emptyMap()),
                    "aiTitle" to JsonObject(emptyMap()),
                    "aiSecurity" to JsonObject(emptyMap()),
                ),
                ruleMeta = mapOf(
                    "titleCheck" to RuleExecutionMeta(concernArea = "title-quality"),
                    "securityCheck" to RuleExecutionMeta(concernArea = "security"),
                    "aiTitle" to RuleExecutionMeta(mode = RuleMode.FALLBACK, concernArea = "title-quality"),
                    "aiSecurity" to RuleExecutionMeta(mode = RuleMode.FALLBACK, concernArea = "security"),
                ),
            )

            val result = engine.validate(makeMR())
            // titleCheck has findings → aiTitle skipped
            // securityCheck has no findings → aiSecurity executes
            assertTrue(result.findings.any { it.rule == "titleCheck" })
            assertTrue(result.findings.any { it.rule == "aiSecurity" })
            assertTrue(result.findings.none { it.rule == "aiTitle" })
            assertTrue(result.skippedRules.any { it.ruleId == "aiTitle" })
        }

    @Test
    fun `SUPPLEMENT rule always executes in pass 2 regardless of concern area`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("titleCheck", listOf(finding("titleCheck", Severity.WARNING))))
            registry.register(stubRule("supplementRule", listOf(finding("supplementRule", Severity.INFO))))

            val engine = buildEngine(
                registry,
                ruleConfigs = mapOf("titleCheck" to JsonObject(emptyMap()), "supplementRule" to JsonObject(emptyMap())),
                ruleMeta = mapOf(
                    "titleCheck" to RuleExecutionMeta(concernArea = "title-quality"),
                    "supplementRule" to RuleExecutionMeta(mode = RuleMode.SUPPLEMENT),
                ),
            )

            val result = engine.validate(makeMR())
            assertEquals(2, result.findings.size)
        }

    @Test
    fun `backwards compat - all NORMAL rules behave as before`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubRule("ruleA", listOf(finding("ruleA", Severity.WARNING))))
            registry.register(stubRule("ruleB", listOf(finding("ruleB", Severity.INFO))))

            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("ruleA" to JsonObject(emptyMap()), "ruleB" to JsonObject(emptyMap())),
                    ruleMeta = mapOf(
                        "ruleA" to RuleExecutionMeta(priority = 1),
                        "ruleB" to RuleExecutionMeta(priority = 10),
                    ),
                )
            )
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })

            val result = engine.validate(makeMR())
            assertEquals(2, result.findings.size)
            assertTrue(result.skippedRules.isEmpty())
        }

    // ── Test-local predicate factories ──────────────────────────────────

    private fun labelExistsPredicate(): ConditionPredicate<ConditionContext> =
        object : ConditionPredicate<ConditionContext> {
            override val name = "label_exists"
            override val arity = 1

            override fun evaluate(
                args: List<String>,
                context: ConditionContext
            ): Boolean {
                val label = args.getOrNull(0) ?: return false
                return context.mr.labels.any { it.equals(label, ignoreCase = true) }
            }
        }

    private fun ruleHasFindingsPredicate(): ConditionPredicate<ConditionContext> =
        object : ConditionPredicate<ConditionContext> {
            override val name = "rule_has_findings"
            override val arity = 1

            override fun evaluate(
                args: List<String>,
                context: ConditionContext
            ): Boolean {
                val ruleId = args.getOrNull(0) ?: return false
                return context.priorFindings.any { it.rule == ruleId }
            }
        }
}
