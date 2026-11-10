package io.karandash.api

import io.karandash.api.module.RuleModule
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleRegistryLoggingTest {
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

    private class Capture : Logger {
        val warnings = mutableListOf<String>()
        val debugs = mutableListOf<String>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            debugs += message
        }

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    @Test
    fun `a module taking over the rule id of another module is warned`() =
        runTest {
            val logger = Capture()
            val registry = ModuleRegistryImpl(logger)
            registry.register(Rule("first", "shared"))
            registry.register(Rule("second", "shared"))
            assertEquals(listOf("Rule 'shared' from module 'first' is now handled by module 'second'"), logger.warnings)
            assertEquals("second", registry.findRuleHandler("shared")?.id)
        }

    @Test
    fun `re-registering the same module id is logged at debug only`() =
        runTest {
            val logger = Capture()
            val registry = ModuleRegistryImpl(logger)
            registry.register(Rule("same", "r"))
            registry.register(Rule("same", "r"))
            assertTrue(logger.warnings.isEmpty(), logger.warnings.toString())
            assertEquals(1, logger.debugs.count { "'same' registered again" in it })
        }
}
