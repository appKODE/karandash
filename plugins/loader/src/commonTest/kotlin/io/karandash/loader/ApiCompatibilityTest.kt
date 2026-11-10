package io.karandash.loader

import io.karandash.api.KarandashApi
import io.karandash.models.support.KarandashJson
import io.karandash.subprocess.BridgeModuleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiCompatibilityTest {
    @Test
    fun `plugin at or below the host level is accepted`() {
        assertNull(apiLevelIncompatibility("p", KarandashApi.MIN_SUPPORTED_LEVEL))
        assertNull(apiLevelIncompatibility("p", KarandashApi.LEVEL))
    }

    @Test
    fun `plugin above the host level is rejected naming plugin and both levels`() {
        val reason = assertNotNull(apiLevelIncompatibility("acme.rule", KarandashApi.LEVEL + 1))
        assertTrue("'acme.rule'" in reason, reason)
        assertTrue("level ${KarandashApi.LEVEL + 1}" in reason, reason)
        assertTrue("provides level ${KarandashApi.LEVEL}" in reason, reason)
    }

    @Test
    fun `bridge module info from an older server defaults minApiLevel`() {
        val json = """{"id":"x","name":"X","version":"1","type":"RULE"}"""
        val info = KarandashJson.Default.decodeFromString(BridgeModuleInfo.serializer(), json)
        assertEquals(KarandashApi.MIN_SUPPORTED_LEVEL, info.minApiLevel)
    }

    @Test
    fun `native ABI range accepts v1 and v2`() {
        assertTrue(NativeAbi.isSupported(1))
        assertTrue(NativeAbi.isSupported(2))
        assertFalse(NativeAbi.isSupported(0))
        assertFalse(NativeAbi.isSupported(3))
        assertEquals("Unsupported ABI version: 3 (supported 1..2)", NativeAbi.unsupportedMessage(3))
    }
}
