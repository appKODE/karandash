@file:OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.karandash.inspector

import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.closeNativeLibrary
import io.karandash.platform.copyTo
import io.karandash.platform.createDirectories
import io.karandash.platform.deleteIfExists
import io.karandash.platform.exists
import io.karandash.platform.openNativeLibrary
import io.karandash.platform.writeBytes
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import platform.posix.setenv
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The native inspector reads plugin files statically and never runs plugin code. */
class NativePluginInspectorStaticTest {
    private val dir = TestFiles.createTestTempDirectory("inspector-static").also { createDirectories(it) }
    private val marker = Path(dir, "trap-sprung")
    private val inspector = NativePluginInspector()

    private fun boobyTrap(): Path {
        val built = Path(checkNotNull(PlatformEnvironment.getEnv("BOOBY_TRAP_LIB")) { "BOOBY_TRAP_LIB not set by Gradle" })
        val lib = Path(dir, "libbooby_trap.${inspector.nativeLibraryExtensions().single()}")
        built.copyTo(lib)
        setenv("BOOBY_TRAP_MARKER", marker.toString(), 1)
        return lib
    }

    @Test
    fun inspectingANativeLibraryNeverLoadsIt() =
        runTest {
            val lib = boobyTrap()

            assertNull(inspector.verifyFile(lib.toString(), lib.name.substringAfterLast('.')))
            assertEquals("libbooby_trap", assertNotNull(inspector.readMetadata(lib.toString())).id)
            assertFalse(marker.exists(), "inspection ran the library constructor")

            // Control: loading the library does spring the trap, so the assertion above is meaningful.
            closeNativeLibrary(checkNotNull(openNativeLibrary(lib.toString())))
            assertTrue(marker.exists(), "booby trap did not fire on dlopen; the test proves nothing")
            deleteIfExists(marker)
        }

    @Test
    fun nativeLibraryWithoutAbiSymbolsIsRejected() =
        runTest {
            val lib = Path(dir, "libnot_a_plugin.dylib").also { it.writeText("just some bytes") }

            assertEquals("Missing required symbol: karandash_abi_version", inspector.verifyFile(lib.toString(), "dylib"))
            assertNull(inspector.readMetadata(lib.toString()))
        }

    @Test
    fun jsThatWouldHangIsNeverEvaluated() =
        runTest {
            val js = Path(dir, "spinner.js").also { it.writeText("while (true) {}") }

            val start = TimeSource.Monotonic.markNow()
            assertNull(inspector.verifyFile(js.toString(), "js"))
            assertEquals("spinner", inspector.readMetadata(js.toString())?.id)
            assertTrue(start.elapsedNow() < 5.seconds)
        }

    @Test
    fun wasmIsCheckedForMagicAndExportsWithoutInstantiating() =
        runTest {
            val wasm = Path(dir, "plugin.wasm")
            wasm.writeBytes(byteArrayOf(0, 'a'.code.toByte(), 's'.code.toByte(), 'm'.code.toByte()) + EXPORT_NAMES)
            val notWasm = Path(dir, "fake.wasm").also { it.writeText("karandash_abi_version karandash_module_id") }

            assertNull(inspector.verifyFile(wasm.toString(), "wasm"))
            assertEquals("plugin", inspector.readMetadata(wasm.toString())?.id)
            assertEquals("Not a WASM module: fake.wasm", inspector.verifyFile(notWasm.toString(), "wasm"))
        }

    @Test
    fun jarNeedsZipMagicAndAModuleServiceEntry() =
        runTest {
            val jar = Path(dir, "plugin.jar")
            jar.writeBytes("PK\u0003\u0004 META-INF/services/io.karandash.api.Module".encodeToByteArray())
            val noServices = Path(dir, "empty.jar").also { it.writeBytes("PK\u0003\u0004 nothing".encodeToByteArray()) }

            assertNull(inspector.verifyFile(jar.toString(), "jar"))
            assertNotNull(inspector.verifyFile(noServices.toString(), "jar"))
            assertNull(inspector.readMetadata(noServices.toString()))
        }

    private companion object {
        val EXPORT_NAMES = "karandash_abi_version karandash_module_id".encodeToByteArray()
    }
}
