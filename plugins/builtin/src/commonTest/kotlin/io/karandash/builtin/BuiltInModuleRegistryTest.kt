package io.karandash.builtin

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.RuleModule
import io.karandash.plugins.parsers.json.JsonFormatModuleLoader
import io.karandash.plugins.parsers.kts.KtsFormatModuleLoader
import io.karandash.plugins.parsers.yaml.YamlFormatModuleLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltInModuleRegistryTest {
    @Test
    fun `every factory builds a module with a unique id and full metadata`() {
        val modules = BuiltInModuleRegistry.factories.map { it(ModuleRegistryImpl()) }

        val duplicates = modules.groupBy { it.id to it.type }.filterValues { it.size > 1 }.keys
        val incomplete = modules.filter { it.id.isBlank() || it.name.isBlank() || it.version.isBlank() }.map { it.id }

        assertEquals(emptySet(), duplicates)
        assertEquals(emptyList(), incomplete)
    }

    @Test
    fun `registering all built ins leaves no alias collisions and every rule resolvable`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val modules = BuiltInModuleRegistry.factories.map { it(registry) }
            modules.forEach { registry.register(it) }

            val rules = modules.filterIsInstance<RuleModule>().flatMap { it.contributedRules() }

            assertEquals(emptyList(), registry.aliasCollisions())
            assertTrue(rules.isNotEmpty())
            assertEquals(emptyList(), rules.filterNot { registry.hasRule(it) })
        }

    /** The manifest builds the formats through the parser modules' loader classes. */
    @Test
    fun `config formats register through their parser module loaders`() {
        val modules = BuiltInModuleRegistry.factories.map { it(ModuleRegistryImpl()) }

        assertEquals(1, modules.count { it is KtsFormatModuleLoader })
        assertEquals(1, modules.count { it is YamlFormatModuleLoader })
        assertEquals(1, modules.count { it is JsonFormatModuleLoader })
    }
}
