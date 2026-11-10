package io.karandash.loader.native

import com.sun.jna.Memory
import com.sun.jna.Pointer
import io.karandash.api.ModuleType
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.RuleModule
import io.karandash.api.module.ScriptModule
import io.karandash.loader.bridge.BridgeConstants
import io.karandash.loader.bridge.JnaCall
import io.karandash.loader.bridge.JnaTransport
import io.karandash.loader.bridge.asRegisteredModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Descriptor parity for the JNA bridge: a fake [JnaCall] answers only the symbols in [strings] / [ints];
 * every other symbol throws [UnsatisfiedLinkError], like a guest built against an older SDK.
 */
class NativeBridgeDescriptorJvmTest {
    private fun fakeLibrary(
        strings: Map<String, String>,
        ints: Map<String, Int> = emptyMap(),
    ): JnaCall =
        { symbol, _, _ ->
            when (symbol) {
                "karandash_free_string" -> null
                in strings -> cString(strings.getValue(symbol))
                in ints -> ints.getValue(symbol)
                else -> throw UnsatisfiedLinkError(symbol)
            }
        }

    private fun cString(value: String): Pointer {
        val bytes = value.encodeToByteArray()
        return Memory(bytes.size + 1L).apply {
            write(0, bytes, 0, bytes.size)
            setByte(bytes.size.toLong(), 0)
        }
    }

    private fun guest(
        type: String,
        describe: String? = null,
    ) = fakeLibrary(
        buildMap {
            put("karandash_module_id", "guest")
            put("karandash_module_name", "Guest")
            put("karandash_module_version", "1.0.0")
            put("karandash_module_type", type)
            describe?.let { put("karandash_describe", it) }
        },
    )

    @Test
    fun rule_describe_reaches_registered_module() {
        val describe =
            """{"id":"guest","types":["RULE"],"properties":{"supportedEnrichment":["jira"],""" +
                """"requiredEnrichment":["git"],"usesExtensionComputedConfig":true,"defaultEnabled":false}}"""
        val rule = assertIs<RuleModule>(NativeModuleBridge.create(guest("RULE", describe)).asRegisteredModules().first())
        assertEquals(listOf("jira"), rule.supportedEnrichment)
        assertEquals(listOf("git"), rule.requiredEnrichment)
        assertTrue(rule.usesExtensionComputedConfig)
        assertFalse(rule.defaultEnabled)
    }

    @Test
    fun analyzer_describe_reaches_registered_module() {
        val describe =
            """{"id":"guest","types":["AI_ANALYZER"],"properties":{"needsScopeDetection":true,"enabledByDefault":true}}"""
        val module = NativeModuleBridge.create(guest("AI_ANALYZER", describe)).asRegisteredModules().first()
        val analyzer = assertIs<AiAnalyzerModule>(module)
        assertTrue(analyzer.needsScopeDetection)
        assertTrue(analyzer.defaultEnabled)
        assertFalse(module is RuleModule)
    }

    @Test
    fun multi_type_guest_registers_one_part_per_declared_type() {
        val describe = """{"id":"guest","types":["RULE","PROFILE","SCRIPT"],"properties":{"defaultEnabled":false}}"""
        val parts = NativeModuleBridge.create(guest("RULE", describe)).asRegisteredModules()

        assertEquals(listOf(ModuleType.RULE, ModuleType.PROFILE, ModuleType.SCRIPT), parts.map { it.type })
        assertIs<RuleModule>(parts[0])
        assertIs<ProfileModule>(parts[1])
        assertIs<ScriptModule>(parts[2])
        assertTrue(parts.all { it.id == "guest" })
    }

    @Test
    fun old_guest_without_describe_symbol_gets_defaults() {
        val rule = assertIs<RuleModule>(NativeModuleBridge.create(guest("RULE")).asRegisteredModules().first())
        assertEquals(emptyList(), rule.supportedEnrichment)
        assertFalse(rule.usesExtensionComputedConfig)
        assertTrue(rule.defaultEnabled, "bridged rule must default to enabled")

        val reporter = NativeModuleBridge.create(guest("REPORTER")).asRegisteredModules().first()
        assertTrue(reporter.defaultEnabled)
        assertFalse(reporter is PlatformServiceModule)
    }

    @Test
    fun jna_int_properties_dispatch_by_name() {
        val lib = fakeLibrary(
            strings = emptyMap(),
            ints = mapOf("karandash_min_api_level" to 3, "karandash_default_priority" to 42),
        )
        val transport = JnaTransport(lib, "guest")
        assertEquals(3, transport.getIntProperty(BridgeConstants.PROP_MIN_API_LEVEL, 0))
        assertEquals(42, transport.getIntProperty("defaultPriority", 0))
        assertEquals(42, transport.getIntProperty("redactorPriority", 0))
        assertEquals(7, transport.getIntProperty("unknown", 7))
    }
}
