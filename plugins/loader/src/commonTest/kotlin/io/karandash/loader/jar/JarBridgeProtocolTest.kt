package io.karandash.loader.jar

import io.karandash.subprocess.BridgeModuleInfo
import io.karandash.subprocess.JarBridgeProtocol
import io.karandash.subprocess.JarBridgeRequest
import io.karandash.subprocess.JarBridgeResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [JarBridgeProtocol] serialization contracts.
 *
 * Verifies that request/response types round-trip through JSON correctly.
 */
class JarBridgeProtocolTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun discoverRequestRoundTrips() {
        val request = JarBridgeRequest(method = JarBridgeProtocol.METHOD_DISCOVER)
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<JarBridgeRequest>(encoded)

        assertEquals(JarBridgeProtocol.METHOD_DISCOVER, decoded.method)
        assertNull(decoded.moduleId)
        assertTrue(decoded.params.isEmpty())
    }

    @Test
    fun invokeRequestWithOpAndArgsRoundTrips() {
        val request = JarBridgeRequest(
            method = JarBridgeProtocol.METHOD_INVOKE,
            moduleId = "com.example.rule",
            params = mapOf(
                JarBridgeProtocol.PARAM_OP to "validate",
                JarBridgeProtocol.PARAM_ARGS to """["title-check","{\"id\":\"1\"}","{}"]""",
            )
        )
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<JarBridgeRequest>(encoded)

        assertEquals(JarBridgeProtocol.METHOD_INVOKE, decoded.method)
        assertEquals("com.example.rule", decoded.moduleId)
        assertEquals("validate", decoded.params[JarBridgeProtocol.PARAM_OP])
        assertTrue(decoded.params[JarBridgeProtocol.PARAM_ARGS].orEmpty().contains("title-check"))
    }

    @Test
    fun successResponseWithModulesRoundTrips() {
        val response = JarBridgeResponse(
            success = true,
            modules = listOf(
                BridgeModuleInfo(
                    id = "com.example.rule",
                    name = "Example Rule",
                    version = "1.0.0",
                    type = "RULE",
                    description = "An example rule module",
                    contributedRules = listOf("title-check", "desc-check"),
                    isDefaultEnabled = true
                )
            )
        )
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JarBridgeResponse>(encoded)

        assertTrue(decoded.success)
        assertNull(decoded.error)
        assertEquals(1, decoded.modules?.size)

        val module = decoded.modules!!.first()
        assertEquals("com.example.rule", module.id)
        assertEquals("RULE", module.type)
        assertEquals(listOf("title-check", "desc-check"), module.contributedRules)
        assertTrue(module.defaultEnabled)
    }

    @Test
    fun errorResponseRoundTrips() {
        val response = JarBridgeResponse(
            success = false,
            error = "ServiceLoader failed: class not found"
        )
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JarBridgeResponse>(encoded)

        assertFalse(decoded.success)
        assertEquals("ServiceLoader failed: class not found", decoded.error)
        assertNull(decoded.modules)
    }

    @Test
    fun invokeResponseWithFindingsResultRoundTrips() {
        val findingsJson = """[{"severity":"ERROR","message":"Bad title","rule":"title-format"}]"""
        val response = JarBridgeResponse(
            success = true,
            data = mapOf(JarBridgeProtocol.DATA_RESULT to findingsJson)
        )
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JarBridgeResponse>(encoded)

        assertTrue(decoded.success)
        val findings = decoded.data?.get(JarBridgeProtocol.DATA_RESULT)
        assertTrue(findings != null)
        assertTrue(findings.contains("Bad title"))
    }

    @Test
    fun invokeResponseWithSuggestionFieldRoundTrips() {
        val findingsJson =
            """[{"severity":"WARNING","message":"Title too short",""" +
                """"rule":"title-format","suggestion":"Add a scope prefix like feat: or fix:"}]"""
        val response = JarBridgeResponse(
            success = true,
            data = mapOf(JarBridgeProtocol.DATA_RESULT to findingsJson)
        )
        val encoded = json.encodeToString(response)
        val decoded = json.decodeFromString<JarBridgeResponse>(encoded)

        assertTrue(decoded.success)
        val findings = decoded.data?.get(JarBridgeProtocol.DATA_RESULT)
        assertTrue(findings != null)
        assertTrue(findings.contains("suggestion"))
        assertTrue(findings.contains("Add a scope prefix like feat: or fix:"))
    }

    @Test
    fun unknownFieldsIgnored() {
        val jsonStr = """{"method":"discover","unknownField":"value","moduleId":null,"params":{}}"""
        val decoded = json.decodeFromString<JarBridgeRequest>(jsonStr)
        assertEquals(JarBridgeProtocol.METHOD_DISCOVER, decoded.method)
    }

    @Test
    fun moduleInfoDefaultValues() {
        val info = BridgeModuleInfo(
            id = "test",
            name = "Test",
            version = "1.0.0",
            type = "RULE"
        )
        assertEquals("", info.description)
        assertEquals(emptyList(), info.contributedRules)
        assertFalse(info.isDefaultEnabled)
    }
}
