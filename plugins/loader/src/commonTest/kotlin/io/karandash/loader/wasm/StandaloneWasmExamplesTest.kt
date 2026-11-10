package io.karandash.loader.wasm

import io.karandash.platform.PlatformEnvironment
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.wasm.WasmMemoryProtocol
import io.karandash.wasm.createWasmEngine
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The shipped standalone WASM examples load and validate under the platform's engine: Chicory on the JVM, Chasm on
 * native (earlier findings: C example returned buffer offsets instead of addresses; Rust example's void
 * `karandash_dealloc` NPE'd in Chicory).
 *
 * The binaries are Gradle build outputs (`EXAMPLE_WASM_DIR`), not committed files.
 */
class StandaloneWasmExamplesTest {
    private val examplesDir = checkNotNull(PlatformEnvironment.getEnv("EXAMPLE_WASM_DIR")) { "EXAMPLE_WASM_DIR not set by Gradle" }

    private fun bytes(example: String): ByteArray =
        SystemFileSystem.source(Path("$examplesDir/$example/plugin.wasm")).buffered().use { it.readByteArray() }

    private fun bridge(example: String): WasmModuleBridge {
        val bytes = bytes(example)
        return WasmModuleBridge(createWasmEngine(bytes), exportNames = WasmExportNames.of(bytes)) { createWasmEngine(bytes) }
    }

    private suspend fun assertValidatesTitles(
        example: String,
        moduleId: String,
        ruleId: String,
    ) {
        val bridge = bridge(example)
        assertEquals(moduleId, bridge.id)
        assertEquals(setOf(ruleId), bridge.contributedRules())
        val bad = bridge.validate(ruleId, createTestMergeRequest(title = "no prefix"), JsonObject(emptyMap()))
        assertTrue(bad.isNotEmpty(), "$example must flag a non-conventional title")
        val good = bridge.validate(ruleId, createTestMergeRequest(title = "feat: ok"), JsonObject(emptyMap()))
        assertEquals(emptyList(), good)
    }

    @Test
    fun c_example_validates() =
        runTest { assertValidatesTitles("standalone-wasm-c-plugin", "wasm-c-standalone-plugin", "wasm-c-standalone-check") }

    @Test
    fun rust_example_validates() =
        runTest { assertValidatesTitles("standalone-wasm-rust-plugin", "wasm-rust-standalone-plugin", "wasm-rust-standalone-check") }

    /** The run shuts rule modules down; the next watch run re-initializes the same bridge instance. */
    @Test
    fun rust_example_reinitializes_after_shutdown() =
        runTest {
            val bridge = bridge("standalone-wasm-rust-plugin")
            val firstInit = bridge.initialize(TestPluginContext())
            bridge.shutdown()
            assertEquals(firstInit, bridge.initialize(TestPluginContext()))
            val mr = createTestMergeRequest(title = "no prefix")
            assertTrue(bridge.validate("wasm-rust-standalone-check", mr, JsonObject(emptyMap())).isNotEmpty())
            bridge.shutdown()
        }

    @Test
    fun linear_memory_round_trips_through_guest_allocation() {
        val engine = createWasmEngine(bytes("standalone-wasm-rust-plugin"))
        val payload = "héllo wasm".encodeToByteArray()
        val (ptr, len) = WasmMemoryProtocol.writeString(engine, payload.decodeToString())
        assertEquals(payload.size, len)
        assertContentEquals(payload, engine.readMemory(ptr, len))
        engine.close()
    }

    /** [WasmModuleBridge] tells optional exports apart by the engine throwing on a missing one. */
    @Test
    fun missing_export_fails() {
        val engine = createWasmEngine(bytes("standalone-wasm-rust-plugin"))
        assertFails { engine.callI32("no_such_export") }
        engine.close()
    }
}
