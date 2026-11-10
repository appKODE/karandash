package com.example.karandash

import io.karandash.api.Module
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Load smoke test: discovers modules exactly the way the host's JAR loader does
 * (ServiceLoader on `io.karandash.api.Module`) and runs the initialize/shutdown lifecycle.
 */
class PluginLoadSmokeTest {
    private val context =
        object : PluginContext {
            override val pluginConfig: JsonElement = JsonObject(emptyMap())
            override val hostVersion: String = "test"
            override val workingDirectory: String = "."

            override fun env(name: String): String? = null
        }

    private val modules: List<Module> = ServiceLoader.load(Module::class.java).toList()

    @Test
    fun `every module listed in the services file is discovered`() {
        val services =
            checkNotNull(javaClass.getResource("/META-INF/services/io.karandash.api.Module"))
                .readText()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(services.sorted(), modules.map { it::class.java.name }.sorted())
    }

    @Test
    fun `module ids are unique`() {
        val ids = modules.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: $ids")
    }

    @Test
    fun `contributed rule ids are camelCase so config keys match them after normalization`() =
        runTest {
            val ruleIds = modules.filterIsInstance<RuleModule>().flatMap { it.contributedRules() }
            assertTrue(ruleIds.isNotEmpty(), "no rule module discovered")
            ruleIds.forEach { assertTrue(it.matches(Regex("[a-z][a-zA-Z0-9]*")), "rule id '$it' is not camelCase") }
        }

    @Test
    fun `every module initializes and shuts down`() =
        runTest {
            modules.forEach { module ->
                assertTrue(module.initialize(context), "${module.id} failed to initialize")
                module.shutdown()
            }
        }
}
