package io.karandash.api

import io.karandash.api.module.RuleModule
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ModuleRegistryCompositeTest {
    private class Rule(
        override val id: String,
        private val ruleId: String,
    ) : RuleModule {
        override val name = id
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(ruleId)

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> = emptyList()
    }

    private class Part(
        override val id: String,
        override val type: ModuleType,
    ) : Module {
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true
    }

    @Test
    fun `every part is found by its type and the primary by id`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val rule = Rule("guest", "guest-rule")
            val profile = Part("guest", ModuleType.PROFILE)
            val script = Part("guest", ModuleType.SCRIPT)
            registry.registerComposite(listOf(rule, profile, script))

            assertSame(rule, registry.get("guest"))
            assertEquals(setOf("guest"), registry.registeredIds())
            assertEquals(listOf<Module>(rule), registry.getByType(ModuleType.RULE))
            assertEquals(listOf<Module>(profile), registry.getByType(ModuleType.PROFILE))
            assertEquals(listOf<Module>(script), registry.getByType(ModuleType.SCRIPT))
            assertSame(rule, registry.findRuleHandler("guest-rule"))
            assertSame(rule, registry.findRuleHandler("guestRule"))
            assertEquals(listOf(rule, profile, script), registry.allModules())
        }

    @Test
    fun `rules contributed by a non-primary part are indexed`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val rule = Rule("guest", "late-rule")
            registry.registerComposite(listOf(Part("guest", ModuleType.SCRIPT), rule))

            assertEquals(ModuleType.SCRIPT, registry.get("guest")?.type)
            assertSame(rule, registry.findRuleHandler("late-rule"))
            assertEquals("late-rule", registry.canonicalRuleId("lateRule"))
        }

    @Test
    fun `unregister removes every part and its indexes`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.registerComposite(listOf(Part("guest", ModuleType.PROFILE), Rule("guest", "guest-rule")))

            assertTrue(registry.unregister("guest"))
            assertFalse(registry.unregister("guest"))
            assertNull(registry.get("guest"))
            assertTrue(registry.getByType(ModuleType.RULE).isEmpty())
            assertTrue(registry.getByType(ModuleType.PROFILE).isEmpty())
            assertFalse(registry.hasRule("guest-rule"))
        }

    @Test
    fun `parts must share one id and there must be at least one`() =
        runTest {
            val registry = ModuleRegistryImpl()
            assertFailsWith<IllegalArgumentException> { registry.registerComposite(emptyList()) }
            assertFailsWith<IllegalArgumentException> {
                registry.registerComposite(listOf(Part("a", ModuleType.PROFILE), Part("b", ModuleType.SCRIPT)))
            }
            assertTrue(registry.registeredIds().isEmpty())
        }

    @Test
    fun `the interface default registers the primary part only`() =
        runTest {
            val inner = ModuleRegistryImpl()
            val legacy = object : ModuleRegistry by inner {
                override suspend fun registerComposite(modules: List<Module>) = super.registerComposite(modules)
            }
            legacy.registerComposite(listOf(Part("guest", ModuleType.PROFILE), Part("guest", ModuleType.SCRIPT)))
            assertEquals(ModuleType.PROFILE, inner.get("guest")?.type)
            assertTrue(inner.getByType(ModuleType.SCRIPT).isEmpty())
        }

    @Test
    fun `concurrent composite registrations all land`() =
        runTest {
            val registry = ModuleRegistryImpl()
            (1..50)
                .map { i ->
                    async { registry.registerComposite(listOf(Rule("g$i", "r$i"), Part("g$i", ModuleType.PROFILE))) }
                }.awaitAll()
            assertEquals(50, registry.registeredIds().size)
            assertEquals(50, registry.getByType(ModuleType.PROFILE).size)
            assertTrue((1..50).all { registry.hasRule("r$it") })
        }

    @Test
    fun `unregister drops the rules indexed at registration without asking a shut down module again`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val shutDown = object : RuleModule {
                override val id = "guest"
                override val name = id
                override val version = "1.0"
                val calls = mutableListOf<String>()

                override suspend fun contributedRules(): Set<String> {
                    check(calls.isEmpty()) { "used after shutdown" }
                    calls += "contributedRules"
                    return setOf("guest-rule")
                }

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun validate(
                    ruleId: String,
                    mr: MergeRequest,
                    configElement: JsonElement,
                ): List<Finding> = emptyList()
            }
            registry.registerComposite(listOf(shutDown, Part("guest", ModuleType.PROFILE)))

            assertTrue(registry.unregister("guest"))
            assertFalse(registry.hasRule("guest-rule"))
            assertEquals(listOf("contributedRules"), shutDown.calls)
        }
}
