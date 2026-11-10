package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.finding.SkipCategory
import io.karandash.conditions.DefaultPolicyEngine
import io.karandash.config.Config
import io.karandash.config.PolicyConfig
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.config.WhenConfig
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Behavior of [PolicyApplicator] on its own: overrides, enablement, severity, stage gates and `when:` filtering. */
class PolicyApplicatorTest {
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

    private fun max(value: Int) = JsonObject(mapOf("max" to JsonPrimitive(value)))

    private val config = Config(
        rules = RulesConfig(ruleConfigs = mapOf("titleLength" to max(10), "labels" to JsonObject(emptyMap()))),
    )

    private fun applicator(
        policies: List<PolicyConfig>,
        base: Config = config,
    ) = PolicyApplicator(
        config = base,
        registry = ModuleRegistryImpl(),
        policyEngine = DefaultPolicyEngine(),
        policies = policies,
        logger = NoOpLogger,
    )

    @Test
    fun noPoliciesLeavesConfigUntouched() =
        runTest {
            val result = applicator(emptyList()).applyPolicies(mr)

            assertSame(config, result.runtimeConfig)
            assertTrue(result.enabledOverrides.isEmpty())
            assertTrue(result.activePolicies.isEmpty())
        }

    @Test
    fun scopedDisableTargetsCanonicalRuleAndKeepsNonRuleIdsAsWritten() =
        runTest {
            val policy = PolicyConfig(condition = MATCHES, modules = listOf("title-length", "format.json"), enabled = false)

            val result = applicator(listOf(policy)).applyPolicies(mr)

            assertEquals(mapOf("titleLength" to false, "format.json" to false), result.enabledOverrides)
            assertEquals(result.enabledOverrides, result.moduleEnabledOverrides)
        }

    @Test
    fun unscopedDisableAppliesToEveryConfiguredRule() =
        runTest {
            val result = applicator(listOf(PolicyConfig(condition = MATCHES, enabled = false))).applyPolicies(mr)

            assertEquals(mapOf("titleLength" to false, "labels" to false), result.enabledOverrides)
            assertTrue(result.moduleEnabledOverrides.isEmpty())
        }

    @Test
    fun matchedOverrideDeepMergesIntoRuleConfig() =
        runTest {
            val policy = PolicyConfig(
                condition = MATCHES,
                override = JsonObject(mapOf("title_length" to JsonObject(mapOf("min" to JsonPrimitive(3))))),
            )

            val rules = applicator(listOf(policy))
                .applyPolicies(mr)
                .runtimeConfig.rules.ruleConfigs

            assertEquals(JsonObject(mapOf("max" to JsonPrimitive(10), "min" to JsonPrimitive(3))), rules["titleLength"])
            assertEquals(config.rules.ruleConfigs["labels"], rules["labels"])
        }

    @Test
    fun unmatchedOverrideIsReportedAsSkippedAndNotApplied() =
        runTest {
            val policy = PolicyConfig(
                condition = NO_MATCH,
                name = "hotfix",
                override = JsonObject(mapOf("titleLength" to max(99))),
            )

            val result = applicator(listOf(policy)).applyPolicies(mr)

            assertSame(config, result.runtimeConfig)
            val skipped = result.skippedPolicyOverrides.single()
            assertEquals("titleLength", skipped.ruleId)
            assertEquals(SkipCategory.POLICY_OVERRIDE_NOT_APPLIED, skipped.category)
            assertTrue("hotfix" in skipped.reason)
        }

    @Test
    fun unmatchedOverrideCarriesThePolicyLabel() =
        runTest {
            val policy = PolicyConfig(
                condition = NO_MATCH,
                name = "hotfix",
                override = JsonObject(mapOf("titleLength" to max(99), "labels" to max(1))),
            )

            val skipped = applicator(listOf(policy)).applyPolicies(mr).skippedPolicyOverrides

            assertEquals(listOf("hotfix", "hotfix"), skipped.map { it.policy })
        }

    @Test
    fun matchedOverrideReportsNoPolicySkip() =
        runTest {
            val policy = PolicyConfig(condition = MATCHES, name = "hotfix", override = JsonObject(mapOf("titleLength" to max(99))))

            assertTrue(applicator(listOf(policy)).applyPolicies(mr).skippedPolicyOverrides.isEmpty())
        }

    @Test
    fun forcedSeverityKeepsOtherMetaFields() =
        runTest {
            val base = config.copy(
                rules = config.rules.copy(ruleMeta = mapOf("titleLength" to RuleExecutionMeta(priority = 5))),
            )
            val policy = PolicyConfig(condition = MATCHES, modules = listOf("TitleLength"), severity = "error")

            val meta = applicator(listOf(policy), base)
                .applyPolicies(mr)
                .runtimeConfig.rules.ruleMeta

            assertEquals(RuleExecutionMeta(priority = 5, severity = "error"), meta["titleLength"])
            assertNull(meta["labels"])
        }

    @Test
    fun stageOverridesUseAndSemanticsAnyFalseWins() =
        runTest {
            val allow = PolicyConfig(condition = MATCHES, autoApprove = true, autoFix = true)
            val deny = PolicyConfig(condition = MATCHES, autoApprove = false, scripts = false)

            val result = applicator(listOf(allow, deny)).applyPolicies(mr)

            assertEquals(false, result.autoApproveOverride)
            assertEquals(false, result.scriptsOverride)
            assertNull(result.autoFixOverride, "true alone never forces a stage on")
            assertNull(result.dangerfileOverride)
        }

    @Test
    fun filterByWhenSkipsFalseRunsTrueAndMergesKebabOverride() {
        val base = config.copy(
            rules = config.rules.copy(
                ruleMeta = mapOf(
                    "titleLength" to RuleExecutionMeta(
                        `when` = WhenConfig(MATCHES, override = JsonObject(mapOf("max-len" to JsonPrimitive(5)))),
                    ),
                    "labels" to RuleExecutionMeta(`when` = WhenConfig(NO_MATCH)),
                ),
            ),
        )

        val result = applicator(emptyList(), base).filterByWhen(base, listOf("titleLength", "labels", "other"), mr, null)

        assertEquals(listOf("titleLength", "other"), result.activeRuleIds)
        val merged = result.runtimeConfig.rules.ruleConfigs
            .getValue("titleLength")
            .jsonObject
        assertEquals(JsonPrimitive(5), merged["maxLen"])
        assertEquals(JsonPrimitive(10), merged["max"])
    }

    @Test
    fun withoutPolicyEngineEvenMatchingPoliciesAreIgnored() =
        runTest {
            val result = PolicyApplicator(
                config = config,
                registry = ModuleRegistryImpl(),
                policyEngine = null,
                policies = listOf(PolicyConfig(condition = MATCHES, enabled = false)),
                logger = NoOpLogger,
            ).applyPolicies(mr)

            assertSame(config, result.runtimeConfig)
            assertTrue(result.enabledOverrides.isEmpty())
        }

    @Test
    fun laterMatchedPolicyWinsEnablementForTheSameModule() =
        runTest {
            val off = PolicyConfig(condition = MATCHES, modules = listOf("labels"), enabled = false)
            val on = PolicyConfig(condition = MATCHES, modules = listOf("labels"), enabled = true)

            val result = applicator(listOf(off, on)).applyPolicies(mr)

            assertEquals(mapOf("labels" to true), result.enabledOverrides)
        }

    @Test
    fun scopedPolicyAppliesOverridesOnlyToItsModules() =
        runTest {
            val policy = PolicyConfig(
                condition = MATCHES,
                modules = listOf("labels"),
                override = JsonObject(
                    mapOf(
                        "labels" to JsonObject(mapOf("required" to JsonPrimitive("x"))),
                        "titleLength" to max(99),
                    ),
                ),
            )

            val rules = applicator(listOf(policy))
                .applyPolicies(mr)
                .runtimeConfig.rules.ruleConfigs

            assertEquals(JsonObject(mapOf("required" to JsonPrimitive("x"))), rules["labels"])
            assertEquals(max(10), rules["titleLength"], "override outside modules: must not apply")
        }

    @Test
    fun overridesOfSuccessivePoliciesComposeAndNonObjectOverridesAreIgnored() =
        runTest {
            val first = PolicyConfig(condition = MATCHES, override = JsonObject(mapOf("titleLength" to max(20))))
            val second = PolicyConfig(
                condition = MATCHES,
                override = JsonObject(
                    mapOf(
                        "titleLength" to JsonObject(mapOf("min" to JsonPrimitive(2))),
                        "labels" to JsonPrimitive("not-an-object"),
                    ),
                ),
            )

            val rules = applicator(listOf(first, second))
                .applyPolicies(mr)
                .runtimeConfig.rules.ruleConfigs

            assertEquals(JsonObject(mapOf("max" to JsonPrimitive(20), "min" to JsonPrimitive(2))), rules["titleLength"])
            assertEquals(JsonObject(emptyMap()), rules["labels"])
        }

    @Test
    fun overrideForAnUnconfiguredRuleCreatesItsConfig() =
        runTest {
            val policy = PolicyConfig(condition = MATCHES, override = JsonObject(mapOf("draftPrevention" to max(1))))

            val rules = applicator(listOf(policy))
                .applyPolicies(mr)
                .runtimeConfig.rules.ruleConfigs

            assertEquals(max(1), rules["draftPrevention"])
        }

    @Test
    fun unscopedSeverityAppliesToEveryConfiguredRule() =
        runTest {
            val policy = PolicyConfig(condition = MATCHES, severity = "warning")

            val meta = applicator(listOf(policy))
                .applyPolicies(mr)
                .runtimeConfig.rules.ruleMeta

            assertEquals("warning", meta["titleLength"]?.severity)
            assertEquals("warning", meta["labels"]?.severity)
        }

    @Test
    fun envLookupFeedsPolicyConditions() =
        runTest {
            val policy = PolicyConfig(condition = "env_equals(\"DEPLOY\", \"prod\")", modules = listOf("labels"), enabled = false)

            fun run(value: String?) =
                PolicyApplicator(
                    config = config,
                    registry = ModuleRegistryImpl(),
                    policyEngine = DefaultPolicyEngine(),
                    policies = listOf(policy),
                    logger = NoOpLogger,
                    envLookup = { if (it == "DEPLOY") value else null },
                )

            assertEquals(mapOf("labels" to false), run("prod").applyPolicies(mr).enabledOverrides)
            assertTrue(run("dev").applyPolicies(mr).enabledOverrides.isEmpty())
        }

    @Test
    fun filterByWhenSeesPriorFindingsThroughRuleResultPredicates() {
        val base = config.copy(
            rules = config.rules.copy(
                ruleMeta = mapOf(
                    "titleLength" to RuleExecutionMeta(`when` = WhenConfig("rule_failed(\"labels\")")),
                    "labels" to RuleExecutionMeta(`when` = WhenConfig("rule_has_findings(\"titleLength\")")),
                ),
            ),
        )
        val prior = listOf(Finding(severity = Severity.ERROR, message = "missing", rule = "labels"))

        val result = applicator(emptyList(), base).filterByWhen(base, listOf("titleLength", "labels"), mr, prior)

        assertEquals(listOf("titleLength"), result.activeRuleIds)
    }

    @Test
    fun ruleResultPredicatesAfterACleanFirstPassSeeEveryRuleAsPassed() {
        val base = config.copy(
            rules = config.rules.copy(
                ruleMeta = mapOf(
                    "titleLength" to RuleExecutionMeta(`when` = WhenConfig("rule_passed(\"labels\")")),
                    "labels" to RuleExecutionMeta(`when` = WhenConfig("rule_failed(\"titleLength\")")),
                ),
            ),
        )

        val result = applicator(emptyList(), base).filterByWhen(base, listOf("titleLength", "labels"), mr, emptyList())

        assertEquals(listOf("titleLength"), result.activeRuleIds)
    }

    @Test
    fun filterByWhenTreatsMalformedExpressionAsNotMet() {
        val base = config.copy(
            rules = config.rules.copy(ruleMeta = mapOf("labels" to RuleExecutionMeta(`when` = WhenConfig("((")))),
        )

        val result = applicator(emptyList(), base).filterByWhen(base, listOf("labels"), mr, null)

        assertTrue(result.activeRuleIds.isEmpty())
        assertSame(base, result.runtimeConfig)
    }
}

/** Matches the fixture MR (`feature/x`); the condition language has no `true`/`false` literals. */
private const val MATCHES = "branch_matches(\"^feature/\")"

private const val NO_MATCH = "branch_matches(\"^hotfix/\")"
