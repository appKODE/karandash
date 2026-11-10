package io.karandash.loader.wasm

import io.karandash.api.ModuleRegistryImpl
import io.karandash.loader.GuestCallTimeout
import io.karandash.loader.RecordingLogger
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Hang protection for [WasmPluginLoader] on Chicory; JVM-only like the other WASM hang tests (lifecycle: commonTest). */
class WasmPluginLoaderLifecycleJvmTest {
    private val dir = java.io.File(TestFiles.createTestTempDirectory("wasm-lifecycle").toString())
    private val registry = ModuleRegistryImpl()
    private val logger = RecordingLogger()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun infiniteLoopInMetadataExportFailsTheLoadWithinTheTimeout() =
        runTest {
            File(dir, "spin.wasm").writeBytes(spinningModuleIdPlugin())
            val loader = WasmPluginLoader(registry, logger, guestCallTimeout = 2.seconds)

            val start = TimeSource.Monotonic.markNow()
            val result = loader.loadFromDirectories(listOf(Path(dir.path)), TestPluginContext())
            assertTrue(start.elapsedNow() < 20.seconds, "load took ${start.elapsedNow()}")

            assertEquals(emptyList(), result.loaded)
            assertEquals("spin.wasm", result.failed.single().path)
            assertTrue("timed out" in result.failed.single().reason, result.failed.single().reason)
            assertEquals(1, logger.warnings.count { "'karandash_module_id' exceeded" in it }, "${logger.warnings}")
            loader.unloadAll()
        }

    /** The metadata reader honours `KARANDASH_PLUGIN_CALL_TIMEOUT` instead of the 30s default. */
    @Test
    fun `metadata read of a spinning plugin stops at the configured guest call timeout`() {
        val file = File(dir, "spin.wasm").apply { writeBytes(spinningModuleIdPlugin()) }
        val env = { name: String -> if (name == GuestCallTimeout.ENV_VAR) "1s" else null }

        val start = TimeSource.Monotonic.markNow()
        val metadata = WasmMetadataReader.readMetadata(Path(file.path), logger, env)
        val elapsed = start.elapsedNow()

        assertNull(metadata)
        assertTrue(elapsed < 15.seconds, "metadata read took $elapsed")
    }

    private companion object {
        /**
         * `karandash_abi_version` returns 1; `karandash_module_id` loops forever.
         * WAT: `(func (result i32) (i32.const 1))`, `(func (result i32) (loop (br 0)) (i32.const 0))`.
         */
        fun spinningModuleIdPlugin(): ByteArray {
            fun export(
                name: String,
                index: Int,
            ) = listOf(name.length) + name.map { it.code } + listOf(0x00, index)
            val exports = listOf(0x02) + export("karandash_abi_version", 0) + export("karandash_module_id", 1)
            val bytes = listOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00) +
                listOf(0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F) +
                listOf(0x03, 0x03, 0x02, 0x00, 0x00) +
                listOf(0x07, exports.size) + exports +
                listOf(0x0A, 0x10, 0x02) +
                listOf(0x04, 0x00, 0x41, 0x01, 0x0B) +
                listOf(0x09, 0x00, 0x03, 0x40, 0x0C, 0x00, 0x0B, 0x41, 0x00, 0x0B)
            return ByteArray(bytes.size) { bytes[it].toByte() }
        }
    }
}
