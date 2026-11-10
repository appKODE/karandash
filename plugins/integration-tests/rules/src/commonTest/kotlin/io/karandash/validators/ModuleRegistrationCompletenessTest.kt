package io.karandash.validators

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.RuleModule
import io.karandash.plugins.ai.AiRuleModule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards against root cause family #1: Registration Drift.
 *
 * When a new rule module is added, it must be registered in:
 * 1. TestEngines.createTestEngine() (test-fixtures)
 * 2. SelfDeclaredDefaultsTest (integration-tests)
 * 3. BuiltInPluginLoader (cli/commonMain) - tested by BuiltInPluginLoaderDriftTest
 *
 * This test verifies (1) and (2) stay in sync with the canonical module list.
 *
 * The canonical list is defined here - when adding a new rule module,
 * add it to [ALL_RULE_MODULE_FACTORIES] and this test will fail if other
 * registration points are missing.
 */
class ModuleRegistrationCompletenessTest {
    companion object {
        /**
         * Canonical list of all built-in rule module factories.
         *
         * **When adding a new rule module, add its factory here.**
         * This is the single source of truth for commonTest.
         */
        val ALL_RULE_MODULE_FACTORIES: List<() -> RuleModule> = listOf(
            { TitleFormatRuleModule() },
            { CommitMessagesRuleModule() },
            { CommitBodyRuleModule() },
            { JiraTicketRuleModule() },
            { BranchNamingRuleModule() },
            { FileChangesRuleModule() },
            { CodeownersRuleModule() },
            { ApprovalRuleModule() },
            { ReviewerRuleModule() },
            { CicdStatusRuleModule() },
            { ConflictRuleModule() },
            { DraftRuleModule() },
            { LabelsRuleModule() },
            { SizeLimitsRuleModule() },
            { SecurityRuleModule() },
            { ContextTagRuleModule() },
            { DescriptionFormatRuleModule() },
            { CrossEntityConsistencyRuleModule() },
            { LanguagePolicyRuleModule() },
            { DependencyValidationRuleModule() },
            { AiRuleModule(ModuleRegistryImpl()) },
        )
    }

    @Test
    fun `createTestEngine registers all canonical rule modules`() =
        runTest {
            val canonicalIds = ALL_RULE_MODULE_FACTORIES.map { it().id }.toSet()

            val testRegistry = ModuleRegistryImpl()
            ALL_RULE_MODULE_FACTORIES.forEach { testRegistry.register(it()) }
            val registeredIds = testRegistry.ruleModules().map { it.id }.toSet()

            val missing = canonicalIds - registeredIds
            assertTrue(
                missing.isEmpty(),
                "Canonical rule modules not registered in test registry: $missing. " +
                    "Update TestEngines.createTestEngine() and this test."
            )
        }

    @Test
    fun `every rule module has a unique id`() {
        val modules = ALL_RULE_MODULE_FACTORIES.map { it() }
        val ids = modules.map { it.id }
        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(
            duplicates.isEmpty(),
            "Duplicate rule module IDs found: $duplicates"
        )
    }

    @Test
    fun `every rule module has a non-empty name`() {
        ALL_RULE_MODULE_FACTORIES.forEach { factory ->
            val module = factory()
            assertTrue(
                module.name.isNotBlank(),
                "Rule module '${module.id}' has a blank name"
            )
        }
    }

    @Test
    fun `every rule module has a non-empty version`() {
        ALL_RULE_MODULE_FACTORIES.forEach { factory ->
            val module = factory()
            assertTrue(
                module.version.isNotBlank(),
                "Rule module '${module.id}' has a blank version"
            )
        }
    }

    /**
     * Modules whose contributedRules() is populated only after initialize().
     * These are excluded from the pre-init contributedRules check.
     */
    private val dynamicContributionModuleIds = setOf("ai-analysis")

    @Test
    fun `every static rule module contributes at least one rule before initialization`() =
        runTest {
            val emptyModules = ALL_RULE_MODULE_FACTORIES
                .map { it() }
                .filter { it.id !in dynamicContributionModuleIds }
                .filter { it.contributedRules().isEmpty() }
                .map { it.id }
            assertTrue(
                emptyModules.isEmpty(),
                "Rule modules with zero contributedRules() before initialize(): $emptyModules. " +
                    "If these populate rules dynamically, add their ids to dynamicContributionModuleIds."
            )
        }

    @Test
    fun `every rule module has callable configSchema`() =
        runTest {
            ALL_RULE_MODULE_FACTORIES.forEach { factory ->
                val module = factory()
                // Must not throw
                module.configSchema()
            }
        }

    @Test
    fun `every rule module has callable defaultConfig`() =
        runTest {
            ALL_RULE_MODULE_FACTORIES.forEach { factory ->
                val module = factory()
                // Must not throw and must not return null
                assertNotNull(
                    module.defaultConfig(),
                    "Rule module '${module.id}' defaultConfig() returned null"
                )
            }
        }
}
