package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.profiles.MinimalProfileModule
import io.karandash.validators.DraftRuleModule
import io.karandash.validators.SizeLimitsRuleModule
import io.karandash.validators.TitleFormatRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZeroConfigDefaultsTest {
    private suspend fun registryWithProfile(): ModuleRegistryImpl {
        val registry = ModuleRegistryImpl()
        registry.register(TitleFormatRuleModule())
        registry.register(DraftRuleModule())
        registry.register(SizeLimitsRuleModule())
        registry.register(MinimalProfileModule())
        return registry
    }

    @Test
    fun `defaultConfig returns valid configuration from registry`() =
        runTest {
            val config = ZeroConfigDefaults.defaultConfig(registryWithProfile())

            assertNotNull(config)
            assertNotNull(config.rules)
            assertTrue(!config.rules.isEmpty())
        }

    @Test
    fun `defaultConfig has no reporters configured`() =
        runTest {
            val config = ZeroConfigDefaults.defaultConfig(registryWithProfile())

            assertNull(config.reporters)
        }

    @Test
    fun `defaultConfig rules are not empty`() =
        runTest {
            val config = ZeroConfigDefaults.defaultConfig(registryWithProfile())

            assertTrue(!config.rules.isEmpty())
        }

    @Test
    fun `defaultConfig throws when no profile is registered`() =
        runTest {
            val emptyRegistry = ModuleRegistryImpl()

            val ex = assertFailsWith<IllegalStateException> {
                ZeroConfigDefaults.defaultConfig(emptyRegistry)
            }
            assertTrue(ex.message!!.contains("No rule modules are registered"))
        }

    @Test
    fun `defaultConfig throws when only rules registered without profile`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(TitleFormatRuleModule())

            val ex = assertFailsWith<IllegalStateException> {
                ZeroConfigDefaults.defaultConfig(registry)
            }
            assertTrue(ex.message!!.contains("No rule modules are registered"))
        }

    @Test
    fun `defaultConfig uses minimal profile when available`() =
        runTest {
            val registry = registryWithProfile()
            val config = ZeroConfigDefaults.defaultConfig(registry)

            assertNotNull(config.rules)
            assertTrue(!config.rules.isEmpty())
        }

    /** The zero-config minimal profile runs at WARNING, so a fresh install never hard-fails. */
    @Test
    fun `zero config rules are lifted to warning severity`() =
        runTest {
            val config = ZeroConfigDefaults.defaultConfig(registryWithProfile())

            assertTrue(config.rules.ruleConfigs.isNotEmpty())
            config.rules.ruleConfigs.keys.forEach { ruleId ->
                assertEquals("warning", config.rules.metaFor(ruleId).severity, "rule $ruleId")
                assertTrue(
                    config.rules.ruleConfigs
                        .getValue(ruleId)
                        .let { "@severity" !in (it as JsonObject) }
                )
            }
        }
}
