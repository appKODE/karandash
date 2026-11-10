package io.karandash.loader.jar

import io.karandash.loader.RecordingLogger
import io.karandash.subprocess.BridgeModuleInfo
import io.karandash.subprocess.JarBridgeProtocol
import io.karandash.subprocess.JarBridgeRequest
import io.karandash.subprocess.JarBridgeResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JAR transport answers properties from the discovery snapshot and sends every other call as one request. */
class JarBridgeTransportTest {
    private val info = BridgeModuleInfo(
        id = "m",
        name = "Module",
        version = "1.2",
        type = "RULE",
        description = "desc",
        exports = listOf("validate", "defaultPriority"),
        abi = mapOf("formatId" to "fmt", "interceptorPriority" to "7", "defaultPriority" to "x", "isDefaultEnabled" to "true"),
    )

    private class Server(
        private val answer: (JarBridgeRequest) -> JarBridgeResponse,
    ) {
        val requests = mutableListOf<JarBridgeRequest>()

        suspend fun send(request: JarBridgeRequest): JarBridgeResponse {
            requests += request
            return answer(request)
        }
    }

    private fun ok(result: String?) = JarBridgeResponse(true, data = result?.let { mapOf(JarBridgeProtocol.DATA_RESULT to it) })

    @Test
    fun `initialize keeps its context for later requests and execute sends its own`() =
        runTest {
            val server = Server { if (it.method == JarBridgeProtocol.METHOD_INITIALIZE) JarBridgeResponse(false) else ok("r") }
            val transport = JarBridgeTransport(server::send, info)
            assertEquals("false", transport.call(JarBridgeProtocol.METHOD_INITIALIZE, "ctx"))
            assertEquals("false", transport.call(JarBridgeProtocol.METHOD_INITIALIZE))
            assertEquals("r", transport.call("validate", "rule", "{}"))
            assertEquals("r", transport.call("execute", "[]", "{}", "own"))
            assertEquals("r", transport.call("execute", "[]"))
            val (_, _, validate, execute, short) = server.requests
            assertEquals("ctx", validate.params[JarBridgeProtocol.PARAM_CONTEXT])
            assertEquals("validate", validate.params[JarBridgeProtocol.PARAM_OP])
            assertEquals("""["rule","{}"]""", validate.params[JarBridgeProtocol.PARAM_ARGS])
            assertEquals("own", execute.params[JarBridgeProtocol.PARAM_CONTEXT])
            assertEquals("ctx", short.params[JarBridgeProtocol.PARAM_CONTEXT])
        }

    @Test
    fun `zero argument calls read the snapshot before asking the server`() =
        runTest {
            val server = Server { ok(null) }
            val transport = JarBridgeTransport(server::send, info)
            assertEquals("fmt", transport.call("formatId"))
            assertEquals("7", transport.call("interceptorPriority"))
            assertNull(transport.call("describe"))
            assertEquals(1, server.requests.size)
        }

    @Test
    fun `properties read after initialize win until shutdown`() =
        runTest {
            val transport = JarBridgeTransport({ JarBridgeResponse(true, data = mapOf("formatId" to "live")) }, info)
            assertEquals("fmt", transport.getProperty("formatId"))
            assertEquals("true", transport.call(JarBridgeProtocol.METHOD_INITIALIZE, "ctx"))
            assertEquals("live", transport.getProperty("formatId"))
            assertEquals("live", transport.call("formatId"))
            assertEquals(7, transport.getIntProperty("interceptorPriority", 0))
            transport.call(JarBridgeProtocol.METHOD_SHUTDOWN)
            assertEquals("fmt", transport.getProperty("formatId"))
        }

    @Test
    fun `failed requests log and read as no answer`() =
        runTest {
            val logger = RecordingLogger()
            val server = Server {
                if (it.method ==
                    JarBridgeProtocol.METHOD_SHUTDOWN
                ) {
                    JarBridgeResponse(false, "gone")
                } else {
                    JarBridgeResponse(false, "boom")
                }
            }
            val transport = JarBridgeTransport(server::send, info, logger)
            assertNull(transport.call(JarBridgeProtocol.METHOD_SHUTDOWN))
            assertNull(transport.call("validate", "a"))
            transport.callVoid("validate", "a")
            val throwing = JarBridgeTransport({ error("pipe closed") }, info, logger)
            throwing.callVoid("validate", "a")
            throwing.logError("validate", IllegalStateException("bad"))
            assertEquals(5, logger.warnings.size, logger.warnings.toString())
            assertTrue(logger.warnings.any { "gone" in it } && logger.warnings.any { "pipe closed" in it })
        }

    @Test
    fun `properties come from the module info then the snapshot`() {
        val transport = JarBridgeTransport({ ok(null) }, info)
        assertEquals("m", transport.getProperty("id"))
        assertEquals("Module", transport.getProperty("name"))
        assertEquals("1.2", transport.getProperty("version"))
        assertEquals("RULE", transport.getProperty("type"))
        assertEquals("desc", transport.getProperty("description"))
        assertEquals("fmt", transport.getProperty("formatId"))
        assertEquals("", transport.getProperty("providerId"))
        assertEquals(7, transport.getIntProperty("interceptorPriority", 0))
        assertEquals(3, transport.getIntProperty("defaultPriority", 3))
        assertEquals(4, transport.getIntProperty("processorPriority", 4))
        assertTrue(transport.getBoolProperty("isDefaultEnabled", false))
        assertFalse(transport.getBoolProperty("formatId", false))
        assertTrue(transport.getBoolProperty("missing", true))
    }

    @Test
    fun `capabilities are lifecycle methods and exported ops or their aliases`() {
        val transport = JarBridgeTransport({ ok(null) }, info)
        assertTrue(transport.hasCapability(JarBridgeProtocol.METHOD_INITIALIZE))
        assertTrue(transport.hasCapability(JarBridgeProtocol.METHOD_SHUTDOWN))
        assertTrue(transport.hasCapability("validate"))
        assertTrue(transport.hasCapability("redactorPriority"))
        assertFalse(transport.hasCapability("report"))
        assertFalse(transport.hasCapability("not-an-op"))
    }
}
