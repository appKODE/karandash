@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.ModuleScope
import io.karandash.api.run.RunServiceKeys
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** The AI pipeline extension is PER_RUN, so each run's per-run state lives on its own handle. */
class AiPipelineExtensionRunScopeTest {
    @Test
    fun `extension is per-run and each run gets a distinct handle`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val prototype = AiPipelineExtension(registry).also { registry.register(it) }
            assertEquals(ModuleScope.PER_RUN, prototype.scope)

            val first = registry.forRun()
            val second = registry.forRun()
            val a = assertNotNull(first.handle(prototype, FakeContext()))
            val b = assertNotNull(second.handle(prototype, FakeContext()))
            assertNotSame(prototype, a)
            assertNotSame(prototype, b)
            assertNotSame(a, b)
            first.close()
            second.close()
        }

    @Test
    fun `forCurrentRun resolves the run handle inside a run and the registry instance outside`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val prototype = AiPipelineExtension(registry).also { registry.register(it) }
            val session = registry.forRun()
            val handle = assertNotNull(session.handle(prototype, FakeContext()))
            session.provide(RunServiceKeys.PIPELINE_EXTENSIONS, listOf(handle))

            assertSame(handle, withContext(session.services) { AiPipelineExtension.forCurrentRun(registry) })
            assertSame(prototype, AiPipelineExtension.forCurrentRun(registry))
            session.close()
        }

    private class FakeContext(
        override val pluginConfig: JsonElement = JsonObject(emptyMap()),
    ) : PluginContext {
        override fun env(name: String): String? = null

        override fun log(message: String) = Unit

        override fun warn(message: String) = Unit

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }
}
