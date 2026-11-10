package io.karandash.loader.js

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.RuleModule
import io.karandash.loader.RecordingLogger
import io.karandash.loader.RecordingPluginContext
import io.karandash.loader.createDirectories
import io.karandash.loader.deleteTestDirectory
import io.karandash.loader.writeText
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Lifecycle invariants and hang protection for [JsPluginLoader]. */
class JsPluginLoaderLifecycleTest {
    private val dir = TestFiles.createTestTempDirectory("js-lifecycle").also { createDirectories(it) }
    private val registry = ModuleRegistryImpl()
    private val logger = RecordingLogger()
    private val context = RecordingPluginContext()

    @AfterTest
    fun cleanup() = deleteTestDirectory(dir)

    /** A CLI_COMMAND plugin: initialized while loading (RULE plugins are initialized later by the pipeline). */
    private fun plugin(
        id: String,
        initResult: Boolean = true,
    ) = Path("$dir/$id.js").writeText(
        """
        var ctx = null;
        module.exports = {
            id: "$id", name: "$id", version: "1.0.0", type: "CLI_COMMAND",
            initialize(c) { ctx = c; c.warn("init:$id"); return $initResult; },
            shutdown() { ctx.warn("shutdown:$id"); },
            cliCommands() { return []; }
        };
        """.trimIndent(),
    )

    @Test
    fun initializeOnLoadShutdownOnUnloadAndUnloadAllIsIdempotent() =
        runTest {
            plugin("js-a")
            plugin("js-b")
            val loader = JsPluginLoader(registry, logger, guestCallTimeout = 10.seconds)

            val result = loader.loadFromDirectories(listOf(dir), context)
            assertEquals(setOf("js-a", "js-b"), result.loaded.toSet())
            assertEquals(listOf("init:js-a", "init:js-b"), context.guestWarnings.sorted())

            assertTrue(loader.unload("js-a"))
            assertNull(registry.get("js-a"))
            assertFalse(loader.unload("js-a"), "second unload of the same module is a no-op")

            loader.unloadAll()
            loader.unloadAll()
            assertNull(registry.get("js-b"))
            assertEquals(1, context.guestWarnings.count { it == "shutdown:js-a" })
            assertEquals(1, context.guestWarnings.count { it == "shutdown:js-b" })
        }

    @Test
    fun failedInitializeIsSkippedAndNeverShutDown() =
        runTest {
            plugin("js-refuses", initResult = false)
            val loader = JsPluginLoader(registry, logger, guestCallTimeout = 10.seconds)

            val result = loader.loadFromDirectories(listOf(dir), context)
            assertEquals(listOf("js-refuses"), result.skipped)
            assertNull(registry.get("js-refuses"))
            loader.unloadAll()
            assertTrue(context.guestWarnings.none { it.startsWith("shutdown:") })
        }

    @Test
    fun infiniteLoopAtLoadFailsThatPluginOnly() =
        runTest {
            Path("$dir/spins-at-load.js").writeText("while (true) {}")
            plugin("js-ok")
            val loader = JsPluginLoader(registry, logger, guestCallTimeout = 2.seconds)

            val start = TimeSource.Monotonic.markNow()
            val result = loader.loadFromDirectories(listOf(dir), context)
            assertTrue(start.elapsedNow() < 20.seconds, "load took ${start.elapsedNow()}")

            assertEquals(listOf("js-ok"), result.loaded)
            assertEquals("spins-at-load.js", result.failed.single().path)
            assertTrue(logger.warnings.any { "exceeded" in it && "KARANDASH_PLUGIN_CALL_TIMEOUT" in it }, "${logger.warnings}")
            loader.unloadAll()
        }

    /** A timed-out call must not abandon the engine, or every later call of the plugin fails. */
    @Test
    fun infiniteLoopInValidateIsInterruptedAndTheEngineKeepsWorking() =
        runTest {
            Path("$dir/js-spin.js").writeText(
                """
                var calls = 0;
                module.exports = {
                    id: "js-spin", name: "js-spin", version: "1.0.0", type: "RULE",
                    contributedRules() { return ["js-spin-rule"]; },
                    validate(ruleId, mr, config) {
                        if (calls++ === 0) { while (true) {} }
                        return [{ severity: "ERROR", message: "after", rule: ruleId }];
                    }
                };
                """.trimIndent(),
            )
            val loader = JsPluginLoader(registry, logger, guestCallTimeout = 2.seconds)
            loader.loadFromDirectories(listOf(dir), context)
            val module = registry.get("js-spin") as RuleModule

            val start = TimeSource.Monotonic.markNow()
            assertEquals(emptyList(), module.validate("js-spin-rule", createTestMergeRequest(), JsonObject(emptyMap())))
            assertTrue(start.elapsedNow() < 20.seconds, "validate took ${start.elapsedNow()}")
            assertEquals(1, logger.warnings.count { "'validate' exceeded" in it }, "${logger.warnings}")

            val findings = module.validate("js-spin-rule", createTestMergeRequest(), JsonObject(emptyMap()))
            assertEquals(listOf("after"), findings.map { it.message }, "${logger.warnings}")

            loader.unloadAll(shutdownTimeout = 2.seconds)
            assertNull(registry.get("js-spin"))
        }
}
