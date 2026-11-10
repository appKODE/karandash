package io.karandash.contracts.fw

import io.karandash.api.ModuleRegistryImpl
import io.karandash.conditions.DefaultPolicyEngine
import io.karandash.config.PolicyConfig
import io.karandash.config.ProfileRef
import io.karandash.config.getRuleConfigElements
import io.karandash.config.pipeline.ProfileResolver
import io.karandash.contracts.support.FwStubProfile
import io.karandash.contracts.support.parseYamlConfig
import io.karandash.engine.PolicyOptions
import io.karandash.engine.ValidationEngine
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestEngine
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.validators.ConflictRuleModule
import io.karandash.validators.SizeLimitsRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Severity overrides do nothing at any layer - per-rule `severity:` (no field in
 * `RuleExecutionMeta`, never applied in `ValidationEngine.enrichFindings`), policy `severity` (`ActivePolicy.forcedSeverity`
 * has no consumer), profile `severity` (`ProfileRef.severityOverride` unused). Per-rule `severity: warning` also
 * makes sizeLimits fail to decode and silently fall back to defaults.
 */
class SeverityOverrideContractTest {
    private val conflictedMr = createTestMergeRequest().copy(hasConflicts = true)

    @Test
    fun `per-rule severity warning downgrades the rule's findings`() =
        runTest {
            val config = parseYamlConfig(
                """
                rules:
                  conflict:
                    severity: warning
                """,
            )
            val result = createTestEngine(config).validate(conflictedMr)
            val finding = assertNotNull(result.findings.firstOrNull { it.rule == ConflictRuleModule.RULE_ID }, "no conflict finding")
            assertEquals(Severity.WARNING, finding.severity, "per-rule `severity: warning` not applied")
            assertTrue(result.passed, "downgraded conflict finding still fails the run")
        }

    @Test
    fun `per-rule severity key does not reset sizeLimits config to defaults`() =
        runTest {
            val config = parseYamlConfig(
                """
                rules:
                  size-limits:
                    max-lines: 10
                    severity: warning
                """,
            )
            val mr = createTestMergeRequest(changes = listOf(FileChange("A.kt", ChangeType.MODIFIED, additions = BIG_CHANGE)))
            val result = createTestEngine(config).validate(mr)
            assertTrue(
                result.findings.any { it.rule == SizeLimitsRuleModule.RULE_ID },
                "`severity: warning` made sizeLimits config fall back to defaults; maxLines=10 not enforced",
            )
        }

    @Test
    fun `matched policy severity is applied to findings`() =
        runTest {
            val config = parseYamlConfig(
                """
                rules:
                  conflict: {}
                """,
            )
            val registry = ModuleRegistryImpl().also { it.register(ConflictRuleModule()) }
            val engine = ValidationEngine(
                config = config,
                registry = registry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
                policy = PolicyOptions(
                    engine = DefaultPolicyEngine(),
                    policies = listOf(
                        PolicyConfig(
                            condition = "branch_matches(\"^feature/\")",
                            modules = listOf(ConflictRuleModule.RULE_ID),
                            severity = "warning",
                        ),
                    ),
                ),
            )
            val result = engine.validate(conflictedMr)
            val finding = assertNotNull(result.findings.firstOrNull { it.rule == ConflictRuleModule.RULE_ID }, "no conflict finding")
            assertEquals(Severity.WARNING, finding.severity, "policy `severity: warning` (forcedSeverity) not applied")
        }

    @Test
    fun `profile severity applies to profile-contributed rules`() =
        runTest {
            val profile = FwStubProfile("fwsev", mapOf(ConflictRuleModule.RULE_ID to JsonObject(emptyMap())))
            val registry = ModuleRegistryImpl().also { it.register(profile) }
            val config = ProfileResolver(registry).apply(parseYamlConfig("{}"), ProfileRef(id = "fwsev", severityOverride = "warning"))
            val result = createTestEngine(config).validate(conflictedMr)
            val finding = assertNotNull(result.findings.firstOrNull { it.rule == ConflictRuleModule.RULE_ID }, "no conflict finding")
            assertEquals(Severity.WARNING, finding.severity, "profile `severity: warning` (severityOverride) not applied")
        }

    private companion object {
        const val BIG_CHANGE = 50
    }
}
