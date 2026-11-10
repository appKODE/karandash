package io.karandash.loader.bridge

import io.karandash.loader.RecordingLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AbiTableTransport] decodes each ABI return type, checks arity and turns guest failures into "no answer". */
class AbiTableTransportTest {
    private class Table(
        private val answers: Map<String, String?>,
        private val missing: Set<String> = emptySet(),
        val log: RecordingLogger = RecordingLogger(),
    ) : AbiTableTransport("m", log, "Test") {
        val calls = mutableListOf<String>()

        override fun exports(op: BridgeOp): Boolean = op.op !in missing

        override fun invoke(
            op: BridgeOp,
            args: Array<out String>,
        ): String? {
            calls += op.op
            if (op.op == "describe") throw IllegalStateException("trap")
            return answers[op.op]
        }
    }

    @Test
    fun `bool results become true or false and unknown methods answer null`() =
        runTest {
            val table = Table(mapOf("initialize" to "1", "isDefaultEnabled" to "0", "formatId" to "fmt"))
            assertEquals("true", table.call("initialize", "{}"))
            assertEquals("false", table.callSync("isDefaultEnabled"))
            assertEquals("fmt", table.call("formatId"))
            assertNull(table.call("no-such-op"))
            assertNull(Table(emptyMap()).call("initialize", "{}"))
        }

    @Test
    fun `wrong arity missing export and a trapping guest answer null`() =
        runTest {
            val table = Table(mapOf("formatId" to "fmt"), missing = setOf("formatId"))
            assertNull(table.call("initialize"))
            assertTrue(
                table.log.warnings
                    .single()
                    .contains("ABI expects 1")
            )
            assertNull(table.call("formatId"))
            assertNull(table.call("describe"))
            assertEquals(listOf("describe"), table.calls)
        }

    @Test
    fun `callVoid warns only on a failed bool status`() =
        runTest {
            val failing = Table(mapOf("addLabels" to "0"))
            failing.callVoid("addLabels", "p", "1", "a")
            failing.callVoid("no-such-op")
            assertEquals(1, failing.log.warnings.size)
            val passing = Table(mapOf("addLabels" to "1", "shutdown" to null))
            passing.callVoid("addLabels", "p", "1", "a")
            passing.callVoid("shutdown")
            Table(emptyMap()).callVoid("addLabels", "p", "1", "a")
            assertTrue(passing.log.warnings.isEmpty())
        }

    @Test
    fun `property getters fall back to their defaults`() {
        val table = Table(mapOf("isDefaultEnabled" to "1", "formatId" to "true", "name" to "n", "minApiLevel" to "3"))
        assertEquals("true", table.getProperty("isDefaultEnabled"))
        assertEquals("n", table.getProperty("name"))
        assertEquals("", table.getProperty("version"))
        assertEquals("", table.getProperty("no-such-op"))
        assertEquals(3, table.getIntProperty("minApiLevel", 9))
        assertEquals(9, table.getIntProperty("name", 9))
        assertEquals(9, table.getIntProperty("version", 9))
        assertEquals(9, table.getIntProperty("no-such-op", 9))
        assertTrue(table.getBoolProperty("isDefaultEnabled", false))
        assertTrue(table.getBoolProperty("formatId", false))
        assertTrue(table.getBoolProperty("name", true))
        assertFalse(table.getBoolProperty("version", false))
        assertTrue(table.getBoolProperty("no-such-op", true))
    }

    @Test
    fun `capability follows the export table`() {
        val table = Table(emptyMap(), missing = setOf("formatId"))
        assertTrue(table.hasCapability("initialize"))
        assertFalse(table.hasCapability("formatId"))
        assertFalse(table.hasCapability("no-such-op"))
    }
}
