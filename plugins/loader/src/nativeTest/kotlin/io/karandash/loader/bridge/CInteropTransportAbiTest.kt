@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.karandash.loader.bridge

import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.openNativeLibrary
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Drives `src/nativeTest/c/ops_plugin.c` (compiled with `-Werror` against the generated
 * `docs/native-sdk/karandash_plugin.h`) through [CInteropTransport]: every arity in [BridgeAbi.OPS],
 * `int32_t` parameters, and STR / INT / BOOL / VOID results reach the C function intact.
 */
class CInteropTransportAbiTest {
    private val transport: CInteropTransport = run {
        val dir = checkNotNull(PlatformEnvironment.getEnv("LIFECYCLE_PLUGIN_DIR")) { "LIFECYCLE_PLUGIN_DIR not set by Gradle" }
        val ext = if (Platform.osFamily == OsFamily.MACOSX) "dylib" else "so"
        CInteropTransport(checkNotNull(openNativeLibrary("$dir/libops.$ext")) { "cannot open libops" }, "ops")
    }

    @AfterTest
    fun close() = transport.closeHandle()

    @Test
    fun theOpTableUsesOnlyTheAritiesExercisedHere() =
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 7), BridgeAbi.OPS.map { it.params.size }.toSet(), "extend callByArity and ops_plugin.c")

    @Test
    fun stringOpsOfEveryArityEchoTheirArguments() =
        runTest {
            assertEquals("ops", transport.call("id"))
            assertEquals("u", transport.call("fetchMergeRequestByUrl", "u"))
            assertEquals("p|m", transport.call("fetchMergeRequest", "p", "m"))
            assertEquals("r|{}|{\"k\":1}", transport.call("validate", "r", "{}", "{\"k\":1}"))
            assertEquals("p|m|t|n|b", transport.call("updateNote", "p", "m", "t", "n", "b"))
            assertEquals(
                "p|m|b|src/A.kt|42|{}|40",
                transport.call("postInlineComment", "p", "m", "b", "src/A.kt", "42", "{}", "40"),
            )
        }

    @Test
    fun intAndBoolResultsAndIntParametersCrossTheAbi() =
        runTest {
            assertEquals("true", transport.call("initialize", "{}"))
            assertEquals("false", transport.call("initialize", "{\"x\":1}"))
            assertEquals("true", transport.call("updateComment", "p", "m", "c", "b"))
            assertEquals("false", transport.call("updateComment", "p", "m", "c", "other"))
            assertEquals("true", transport.call("setDraft", "p", "m", "true"))
            assertEquals("false", transport.call("setDraft", "p", "m", "false"))
            assertEquals("6", transport.call("deleteMarkedInlineComments", "p", "m", "marker"))
        }

    @Test
    fun voidOpsRunAndAbsentOpsAreReported() =
        runTest {
            val before = checkNotNull(transport.call("description")).toInt()
            transport.callVoid("onPipelineComplete")
            assertEquals(before + 1, checkNotNull(transport.call("description")).toInt())
            assertFalse(transport.hasCapability("report"), "ops_plugin does not export karandash_report")
        }
}
