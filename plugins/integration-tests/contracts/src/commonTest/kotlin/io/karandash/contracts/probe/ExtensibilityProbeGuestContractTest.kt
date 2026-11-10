package io.karandash.contracts.probe

import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.module.RuleModule
import io.karandash.loader.js.JsPluginLoader
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JS guest probe: a guest plugin loaded through the real [JsPluginLoader] must surface exactly
 * what it declares. Today `JsModuleBridge` implements every SDK interface and stubs the rest, so the
 * declarations of `tools/examples/extensibility-probe/js/probe-plugin.mjs` are lost.
 */
class ExtensibilityProbeGuestContractTest {
    private val repoRoot = checkNotNull(TestFiles.findRepoRoot()) { "JS guest probe: no settings.gradle.kts above the working dir" }

    @Test
    fun `guest supportedEnrichment is forwarded to the host`() =
        runTest {
            withProbeGuest { module ->
                val analyzer = assertIs<AiAnalyzerModule>(module, "JS guest probe: probe guest is not an AiAnalyzerModule")
                assertEquals(
                    listOf("probe.ticket", "probe.owners"),
                    analyzer.supportedEnrichment,
                    "JS guest probe: guest supportedEnrichment dropped by the JS bridge",
                )
            }
        }

    @Test
    fun `guest requiredEnrichment is forwarded to the host`() =
        runTest {
            withProbeGuest { module ->
                val analyzer = assertIs<AiAnalyzerModule>(module, "JS guest probe: probe guest is not an AiAnalyzerModule")
                assertEquals(
                    listOf("probe.ticket"),
                    analyzer.requiredEnrichment,
                    "JS guest probe: guest requiredEnrichment dropped by the JS bridge",
                )
            }
        }

    @Test
    fun `analyzer-only guest is not exposed as a RuleModule`() =
        runTest {
            withProbeGuest { module ->
                assertFalse(
                    module is RuleModule,
                    "JS guest probe: AI_ANALYZER-only guest is registered as a RuleModule (phantom capability)",
                )
            }
        }

    @Test
    fun `guest needsScopeDetection is forwarded to the host`() =
        runTest {
            withProbeGuest { module ->
                val analyzer = assertIs<AiAnalyzerModule>(module, "JS guest probe: probe guest is not an AiAnalyzerModule")
                assertTrue(analyzer.needsScopeDetection, "JS guest probe: guest needsScopeDetection dropped by the JS bridge")
            }
        }

    private suspend fun withProbeGuest(block: suspend (Module) -> Unit) {
        val registry = ModuleRegistryImpl()
        val loader = JsPluginLoader(registry)
        try {
            val result = loader.loadFromDirectories(listOf(Path(repoRoot, PROBE_JS_DIR)), PROBE_CONTEXT)
            assertTrue(
                GUEST_ID in result.loaded,
                "JS guest probe: probe guest failed to load from $PROBE_JS_DIR: failed=${result.failed} skipped=${result.skipped}",
            )
            block(assertNotNull(registry.get(GUEST_ID), "JS guest probe: probe guest not in registry"))
        } finally {
            loader.unloadAll()
        }
    }

    private companion object {
        /** Relative to the repo root, found from the working dir (`contractTest` runs in the root, native in the module). */
        const val PROBE_JS_DIR = "tools/examples/extensibility-probe/js"
        const val GUEST_ID = "extensibility-probe-js"

        val PROBE_CONTEXT =
            object : PluginContext {
                override val pluginConfig: JsonElement = JsonObject(emptyMap())

                override fun env(name: String): String? = null

                override val hostVersion: String = "0.0.0-contract"
                override val workingDirectory: String = "."
            }
    }
}
