package io.karandash.validators

import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.RuleModule
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.plugins.ai.AiRuleModule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards against root cause family #2: ID Normalization Mismatch.
 *
 * Verifies that every rule ID contributed by every rule module is resolvable
 * through kebab-case, camelCase, snake_case, and PascalCase lookups in [ModuleRegistryImpl].
 *
 * ConfigMapParser normalizes YAML/JSON keys to camelCase, while modules like
 * JiraRuleModule declare kebab-case IDs (`jira-status-check`). The registry
 * must index both forms or dispatch fails silently.
 */
class RuleIdDispatchConsistencyTest {
    private val allRuleModules: List<RuleModule> = listOf(
        TitleFormatRuleModule(),
        CommitMessagesRuleModule(),
        CommitBodyRuleModule(),
        JiraTicketRuleModule(),
        BranchNamingRuleModule(),
        FileChangesRuleModule(),
        CodeownersRuleModule(),
        ApprovalRuleModule(),
        ReviewerRuleModule(),
        CicdStatusRuleModule(),
        ConflictRuleModule(),
        DraftRuleModule(),
        LabelsRuleModule(),
        SizeLimitsRuleModule(),
        SecurityRuleModule(),
        ContextTagRuleModule(),
        DescriptionFormatRuleModule(),
        CrossEntityConsistencyRuleModule(),
        LanguagePolicyRuleModule(),
        DependencyValidationRuleModule(),
    )

    private suspend fun buildRegistry(): ModuleRegistryImpl {
        val registry = ModuleRegistryImpl()
        allRuleModules.forEach { registry.register(it) }
        registry.register(AiRuleModule(registry))
        return registry
    }

    @Test
    fun `every contributed rule ID is resolvable by its original form`() =
        runTest {
            val registry = buildRegistry()
            for (module in allRuleModules) {
                for (ruleId in module.contributedRules()) {
                    assertNotNull(
                        registry.findRuleHandler(ruleId),
                        "Rule ID '$ruleId' from ${module.id} is not resolvable in the registry"
                    )
                }
            }
        }

    @Test
    fun `every contributed rule ID is resolvable by its camelCase form`() =
        runTest {
            val registry = buildRegistry()
            for (module in allRuleModules) {
                for (ruleId in module.contributedRules()) {
                    val camelId = kebabToCamelCase(ruleId)
                    assertNotNull(
                        registry.findRuleHandler(camelId),
                        "Rule ID '$ruleId' (camelCase: '$camelId') from ${module.id} " +
                            "is not resolvable via camelCase lookup. " +
                            "ConfigMapParser normalizes config keys to camelCase — this ID will silently fail."
                    )
                }
            }
        }

    @Test
    fun `every contributed rule ID is resolvable by its snake_case form`() =
        runTest {
            val registry = buildRegistry()
            for (module in allRuleModules) {
                for (ruleId in module.contributedRules()) {
                    val snakeId = ruleId.replace('-', '_')
                    assertNotNull(
                        registry.findRuleHandler(snakeId),
                        "Rule ID '$ruleId' (snake_case: '$snakeId') from ${module.id} " +
                            "is not resolvable via snake_case lookup."
                    )
                }
            }
        }

    @Test
    fun `every contributed rule ID is resolvable by its PascalCase form`() =
        runTest {
            val registry = buildRegistry()
            for (module in allRuleModules) {
                for (ruleId in module.contributedRules()) {
                    val pascalId = ruleId.split('-').joinToString("") {
                        it.replaceFirstChar { ch -> ch.uppercaseChar() }
                    }
                    assertNotNull(
                        registry.findRuleHandler(pascalId),
                        "Rule ID '$ruleId' (PascalCase: '$pascalId') from ${module.id} " +
                            "is not resolvable via PascalCase lookup."
                    )
                }
            }
        }

    @Test
    fun `no contributed rule ID collides with another module`() =
        runTest {
            val idToModule = mutableMapOf<String, String>()
            for (module in allRuleModules) {
                for (ruleId in module.contributedRules()) {
                    val existing = idToModule[ruleId]
                    if (existing != null) {
                        assertTrue(
                            existing == module.id,
                            "Rule ID '$ruleId' claimed by both '$existing' and '${module.id}'"
                        )
                    }
                    idToModule[ruleId] = module.id
                }
            }
        }

    @Test
    fun `canonicalRuleId round-trips through camelCase normalization`() =
        runTest {
            val registry = buildRegistry()
            for (module in allRuleModules) {
                for (ruleId in module.contributedRules()) {
                    val camelId = kebabToCamelCase(ruleId)
                    val canonical = registry.canonicalRuleId(camelId)
                    assertNotNull(
                        registry.findRuleHandler(canonical),
                        "Canonical ID '$canonical' (from camel '$camelId', original '$ruleId') " +
                            "is not resolvable in the registry"
                    )
                }
            }
        }

    @Test
    fun `ModuleIdNormalizer and Module normalizeId produce identical results for all rule IDs`() =
        runTest {
            for (module in allRuleModules) {
                for (ruleId in module.contributedRules()) {
                    assertEquals(
                        Module.normalizeId(ruleId),
                        ModuleIdNormalizer.normalize(ruleId),
                        "ModuleIdNormalizer.normalize('$ruleId') differs from Module.normalizeId('$ruleId') " +
                            "— the two implementations must stay in sync."
                    )
                }
            }
        }

    private fun kebabToCamelCase(key: String): String = ConfigKeyNormalizer.kebabToCamelCase(key)
}
