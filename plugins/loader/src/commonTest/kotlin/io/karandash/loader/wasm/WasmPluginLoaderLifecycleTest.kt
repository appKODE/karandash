package io.karandash.loader.wasm

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.RuleModule
import io.karandash.loader.RecordingLogger
import io.karandash.platform.PlatformEnvironment
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Lifecycle invariants for [WasmPluginLoader] on the platform's engine (Chicory on JVM, Chasm on native). */
class WasmPluginLoaderLifecycleTest {
    private val dir = TestFiles.createTestTempDirectory("wasm-lifecycle")
    private val registry = ModuleRegistryImpl()
    private val logger = RecordingLogger()

    @AfterTest
    fun cleanup() {
        TestFiles.deleteRecursively(dir)
    }

    private fun copyExample(name: String) {
        val bytes = SystemFileSystem
            .source(
                Path(
                    "${checkNotNull(
                        PlatformEnvironment.getEnv("EXAMPLE_WASM_DIR")
                    ) { "EXAMPLE_WASM_DIR not set by Gradle" }}/standalone-wasm-rust-plugin/plugin.wasm"
                )
            ).buffered()
            .use {
                it.readByteArray()
            }
        SystemFileSystem.sink(Path(dir, name)).buffered().use { it.write(bytes) }
    }

    @Test
    fun loadUnloadAndUnloadAllFollowTheModuleLifecycle() =
        runTest {
            copyExample("rust.wasm")
            val loader = WasmPluginLoader(registry, logger, guestCallTimeout = 10.seconds)

            val result = loader.loadFromDirectories(listOf(dir), TestPluginContext())
            assertEquals(listOf(MODULE_ID), result.loaded)
            val module = assertNotNull(registry.get(MODULE_ID) as? RuleModule)
            val findings = module.validate(RULE_ID, createTestMergeRequest(title = "no prefix"), JsonObject(emptyMap()))
            assertTrue(findings.isNotEmpty())

            assertTrue(loader.unload(MODULE_ID))
            assertNull(registry.get(MODULE_ID))
            assertFalse(loader.unload(MODULE_ID), "second unload of the same module is a no-op")
            loader.unloadAll()
            loader.unloadAll()
            assertTrue(logger.warnings.isEmpty(), "${logger.warnings}")
        }

    @Test
    fun duplicateIdIsSkippedAndLoaderStaysUsable() =
        runTest {
            copyExample("rust.wasm")
            copyExample("rust-copy.wasm")
            val loader = WasmPluginLoader(registry, logger, guestCallTimeout = 10.seconds)

            val result = loader.loadFromDirectories(listOf(dir), TestPluginContext())
            assertEquals(listOf(MODULE_ID), result.loaded)
            assertEquals(listOf(MODULE_ID), result.skipped)
            loader.unloadAll()
            assertNull(registry.get(MODULE_ID))
        }

    private companion object {
        const val MODULE_ID = "wasm-rust-standalone-plugin"
        const val RULE_ID = "wasm-rust-standalone-check"
    }
}
