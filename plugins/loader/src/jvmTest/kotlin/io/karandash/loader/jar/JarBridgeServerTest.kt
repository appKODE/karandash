package io.karandash.loader.jar

import io.karandash.subprocess.JarBridgeProtocol
import io.karandash.subprocess.JarBridgeRequest
import io.karandash.subprocess.JarBridgeResponse
import io.karandash.testfixtures.NativeUnsupported
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [JarBridgeServer] request handling.
 *
 * Exercises the server in-process by redirecting [System.in] and [System.out],
 * avoiding the need to spawn a real JVM subprocess.
 *
 * The server is a Kotlin `object` with shared state.
 * Each test cleans up via a global shutdown call in [afterEach].
 * Marked [NativeUnsupported]: it swaps `System.in`/`System.out`, which only the JVM has.
 */
@NativeUnsupported("redirects System.in and System.out of the JVM")
class JarBridgeServerTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @AfterTest
    fun afterEach() {
        // Clear shared state between tests using the public API.
        invoke("""{"method":"shutdown","params":{}}""")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun invoke(jsonRequest: String): JarBridgeResponse {
        val inputBytes = jsonRequest.toByteArray()
        val inputStream = inputBytes.inputStream()
        val outputStream = ByteArrayOutputStream()

        val originalIn = System.`in`
        val originalOut = System.out
        System.setIn(inputStream)
        System.setOut(PrintStream(outputStream))
        try {
            JarBridgeServer.main(emptyArray())
        } finally {
            System.setIn(originalIn)
            System.setOut(originalOut)
        }

        return json.decodeFromString(outputStream.toString(Charsets.UTF_8).trim())
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun discover_returnsSuccessWithModuleList() {
        // ServiceLoader finds no test modules in the loader test classpath -
        // but the response must still be success=true with a non-null modules list.
        val response = invoke("""{"method":"${JarBridgeProtocol.METHOD_DISCOVER}","params":{}}""")
        assertTrue(response.success)
        assertNotNull(response.modules)
    }

    @Test
    fun invoke_forUnknownModule_returnsError() {
        val request = json.encodeToString(
            JarBridgeRequest(
                method = JarBridgeProtocol.METHOD_INVOKE,
                moduleId = "com.example.definitely.nonexistent",
                params = mapOf(
                    JarBridgeProtocol.PARAM_OP to "validate",
                    JarBridgeProtocol.PARAM_ARGS to """["title-check","{}","{}"]""",
                ),
            )
        )
        val response = invoke(request)
        assertFalse(response.success)
        assertTrue(
            response.error?.contains("not found", ignoreCase = true) == true,
            "Expected 'not found' in error, got: ${response.error}",
        )
    }

    @Test
    fun invalidJson_returnsErrorResponse() {
        val response = invoke("not-valid-json{{{{")
        assertFalse(response.success)
        assertTrue(
            response.error?.contains("Invalid request", ignoreCase = true) == true,
            "Expected 'Invalid request' in error, got: ${response.error}",
        )
    }

    @Test
    fun unknownMethod_returnsError() {
        val response = invoke("""{"method":"no-such-method","params":{}}""")
        assertFalse(response.success)
        assertTrue(
            response.error?.contains("Unknown method", ignoreCase = true) == true,
            "Expected 'Unknown method' in error, got: ${response.error}",
        )
    }

    @Test
    fun shutdown_forSpecificUnknownModule_returnsSuccess() {
        // Shutdown is idempotent - unknown module IDs are silently ignored.
        val request = json.encodeToString(
            JarBridgeRequest(
                method = JarBridgeProtocol.METHOD_SHUTDOWN,
                moduleId = "nonexistent.module",
                params = emptyMap(),
            )
        )
        val response = invoke(request)
        assertTrue(response.success)
    }

    @Test
    fun shutdown_withoutModuleId_clearsAllAndReturnsSuccess() {
        val response = invoke("""{"method":"${JarBridgeProtocol.METHOD_SHUTDOWN}","params":{}}""")
        assertTrue(response.success)
    }

    @Test
    fun configSchemaOp_forUnknownModule_returnsError() {
        val request = json.encodeToString(
            JarBridgeRequest(
                method = JarBridgeProtocol.METHOD_INVOKE,
                moduleId = "com.example.nonexistent",
                params = mapOf(JarBridgeProtocol.PARAM_OP to "configSchema"),
            )
        )
        val response = invoke(request)
        assertFalse(response.success)
        assertTrue(
            response.error?.contains("not found", ignoreCase = true) == true,
            "Expected 'not found' in error, got: ${response.error}",
        )
    }

    @Test
    fun initialize_withoutModuleId_returnsError() {
        val response = invoke("""{"method":"${JarBridgeProtocol.METHOD_INITIALIZE}","params":{}}""")
        assertFalse(response.success)
        assertTrue(response.error != null)
    }

    @Test
    fun invoke_withoutModuleId_returnsError() {
        val response = invoke("""{"method":"${JarBridgeProtocol.METHOD_INVOKE}","params":{"op":"validate"}}""")
        assertFalse(response.success)
        assertTrue(response.error != null)
    }

    @Test
    fun requestRoundTrip_discoverResponseDeserializesCorrectly() {
        // Verifies the full serialization round-trip: request → server → response.
        val request = JarBridgeRequest(method = JarBridgeProtocol.METHOD_DISCOVER)
        val response = invoke(json.encodeToString(request))
        assertTrue(response.success)
        val modules = response.modules
        assertNotNull(modules)
        // Modules list must be a valid list (empty in test context is expected)
        assertTrue(modules.all { it.id.isNotBlank() })
    }
}
