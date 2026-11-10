package io.karandash.loader.bridge

import io.karandash.api.ModuleType
import io.karandash.loader.RecordingPluginContext
import io.karandash.loader.bridge.modules.FakeBridgeTransport
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Metadata, lifecycle and config-format calls of the transport-backed bridge and its shared [BridgeModuleCore]. */
class TransportModuleBridgeTest {
    private fun bridge(
        guest: FakeBridgeTransport,
        type: ModuleType = ModuleType.RULE,
        onClose: () -> Unit = {},
    ) = TransportModuleBridge(
        transport = guest,
        id = "g",
        name = "Guest",
        version = "1.0",
        type = type,
        descriptor = BridgeDescriptor.parse("""{"id":"g","types":["$type"],"properties":{"supportedEnrichment":["jira"]}}""", "g"),
        onClose = onClose,
    )

    @Test
    fun `metadata falls back to defaults and the guest identity`() {
        val module = bridge(FakeBridgeTransport(props = FakeBridgeTransport.IDENTITY))
        assertEquals(100, module.defaultPriority)
        assertEquals(
            listOf(100, 100, 100, 100, 100),
            with(module) {
                listOf(interceptorPriority, processorPriority, transformerPriority, enricherPriority, redactorPriority)
            }
        )
        assertEquals(listOf("g", "g", "g", "g", "g"), with(module) { listOf(profileId, providerId, analyzerId, reportId, metricId) })
        assertEquals("Guest" to "Guest", module.reportName to module.metricName)
        assertEquals(Severity.WARNING, module.defaultSeverity)
        assertNull(module.apiKeyEnvVar)
        assertNull(module.defaultPrompt)
        assertEquals("g", module.featureKey)
        assertEquals(listOf("jira"), module.supportedEnrichment)
        assertEquals(emptyList(), module.requiredEnrichment)
        assertTrue(module.defaultEnabled)
        assertFalse(module.isDefaultZeroConfigProfile)
        assertEquals(listOf("", "", "", ""), with(module) { listOf(summary, defaultModel, unit, formatId) })
        assertNull(module.baseUrlEnvVar)
        assertTrue(module.minApiLevel > 0)
    }

    @Test
    fun `metadata reads guest properties`() {
        val props = mapOf(
            "defaultPriority" to "5",
            "redactorPriority" to "6",
            "formatId" to "md",
            "configFormatId" to "toml",
            "minApiLevel" to "3"
        )
        val module = bridge(FakeBridgeTransport(props = props))
        assertEquals(5 to 6, module.defaultPriority to module.redactorPriority)
        assertEquals("md" to "toml", module.formatIdFor(ModuleType.REPORTER) to module.formatIdFor(ModuleType.CONFIG_FORMAT))
        assertEquals(3, module.minApiLevel)
    }

    @Test
    fun `initialize reports the guest answer and shutdown and close reach the guest`() =
        runTest {
            val closed = mutableListOf<Boolean>()
            val guest = FakeBridgeTransport(mapOf("initialize" to "true"))
            val module = bridge(guest, onClose = { closed += true })
            assertTrue(module.initialize(RecordingPluginContext()))
            module.shutdown()
            module.close()
            assertEquals(listOf("initialize", "shutdown"), guest.calls.map { it.first })
            assertEquals(listOf(true), closed)
            assertFalse(bridge(FakeBridgeTransport()).initialize(RecordingPluginContext()))
            assertEquals(emptyList(), BridgeConfigFormatPart(FakeBridgeTransport()).cliCommands())
        }

    @Test
    fun `config format calls parse objects and tolerate bad replies`() =
        runTest {
            val format = BridgeConfigFormatPart(
                FakeBridgeTransport(
                    results = mapOf("configParse" to """{"extends":"base","a":1}""", "configParseExtends" to "\"x\""),
                    props = mapOf("configFormatExtensions" to """["toml"]"""),
                ),
            )
            assertEquals(setOf("toml"), format.fileExtensions)
            assertEquals(JsonPrimitive(1), (format.parseToJsonElement("c") as JsonObject)["a"])
            assertEquals(JsonPrimitive("x"), format.parseExtendsOnly("c"))

            val fallback = BridgeConfigFormatPart(FakeBridgeTransport(mapOf("configParse" to """{"extends":["b"]}""")))
            assertEquals(JsonArray(listOf(JsonPrimitive("b"))), fallback.parseExtendsOnly("c"))

            val bad = BridgeConfigFormatPart(
                FakeBridgeTransport(mapOf("configParse" to "[1]", "configParseExtends" to "{"), mapOf("configFormatExtensions" to "{")),
            )
            assertEquals(emptySet(), bad.fileExtensions)
            assertEquals(JsonObject(emptyMap()), bad.parseToJsonElement("c"))
            assertNull(bad.parseExtendsOnly("c"))
            assertEquals(
                JsonObject(emptyMap()),
                BridgeConfigFormatPart(FakeBridgeTransport(mapOf("configParse" to "{"))).parseToJsonElement("c")
            )
            assertEquals(emptySet(), BridgeConfigFormatPart(FakeBridgeTransport()).fileExtensions)
            assertNull(BridgeConfigFormatPart(FakeBridgeTransport()).parseExtendsOnly("c"))
        }

    @Test
    fun `module core reads identity and degrades without capabilities`() =
        runTest {
            val props = mapOf("id" to "g", "name" to "Guest", "version" to "1.0", "type" to "rule")
            val bare = BridgeModuleCore(FakeBridgeTransport(props = props, capabilities = emptySet()))
            assertEquals(listOf("g", "Guest", "1.0", "Guest"), listOf(bare.id, bare.name, bare.version, bare.description))
            assertEquals(ModuleType.RULE, bare.type)
            assertTrue(bare.initialize(RecordingPluginContext()))
            bare.shutdown()
            assertEquals(JsonObject(emptyMap()), bare.defaultConfig())
            assertEquals(emptyList(), bare.configSchema())
            assertEquals(emptyList(), bare.messages())
        }

    @Test
    fun `module core forwards lifecycle calls and parses config replies`() =
        runTest {
            val props = mapOf("type" to "reporter", "description" to "D", "configSchema" to "[]")
            val guest = FakeBridgeTransport(mapOf("initialize" to "false", "defaultConfig" to """{"a":1}""", "messages" to "[]"), props)
            val core = BridgeModuleCore(guest)
            assertEquals("D" to ModuleType.REPORTER, core.description to core.type)
            assertFalse(core.initialize(RecordingPluginContext()))
            assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1))), core.defaultConfig())
            assertEquals(emptyList(), core.configSchema())
            assertEquals(emptyList(), core.messages())
            core.shutdown()
            assertEquals(
                JsonObject(emptyMap()),
                BridgeModuleCore(FakeBridgeTransport(mapOf("defaultConfig" to "{"), props)).defaultConfig()
            )
            assertEquals(JsonObject(emptyMap()), BridgeModuleCore(FakeBridgeTransport(props = props)).defaultConfig())
            assertTrue(BridgeModuleCore(FakeBridgeTransport(props = props)).initialize(RecordingPluginContext()))
            BridgeModuleCore(FakeBridgeTransport(props = props, fail = true)).shutdown()
        }

    @Test
    fun `module core rejects an unknown module type`() {
        val error = assertFailsWith<IllegalArgumentException> { BridgeModuleCore(FakeBridgeTransport(props = mapOf("type" to "nope"))) }
        assertTrue(error.message.orEmpty().startsWith("Unsupported plugin"))
    }
}
