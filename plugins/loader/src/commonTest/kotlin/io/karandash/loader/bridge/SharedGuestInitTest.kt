package io.karandash.loader.bridge

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.EnrichedReporterModule
import io.karandash.api.module.ReporterModule
import io.karandash.loader.RecordingLogger
import io.karandash.loader.RecordingPluginContext
import io.karandash.loader.bridge.modules.BridgeEnrichedReporterModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Composite bridge parts share one guest: one init with the merged part configs, per-part defaults. */
@OptIn(ExperimentalKarandashApi::class)
class SharedGuestInitTest {
    private fun context(vararg entries: Pair<String, String>): PluginContext =
        object : PluginContext by RecordingPluginContext() {
            override val pluginConfig: JsonElement = JsonObject(entries.associate { (k, v) -> k to JsonPrimitive(v) })
        }

    private class Recorder {
        val configs = mutableListOf<JsonElement>()

        suspend fun init(context: PluginContext): Boolean {
            configs += context.pluginConfig
            return true
        }
    }

    @Test
    fun `a composite guest gets the union of part configs and is not re-initialized with the same one`() =
        runTest {
            val shared = SharedGuestInit(composite = true)
            val guest = Recorder()

            shared.initialize(context("rule" to "a"), guest::init)
            shared.initialize(context("reporter" to "b"), guest::init)
            shared.initialize(context("reporter" to "b"), guest::init)

            assertEquals<List<JsonElement>>(
                listOf(
                    JsonObject(mapOf("rule" to JsonPrimitive("a"))),
                    JsonObject(mapOf("rule" to JsonPrimitive("a"), "reporter" to JsonPrimitive("b"))),
                ),
                guest.configs,
            )
        }

    @Test
    fun `the calling part wins a clashing key and reset forgets the merge`() =
        runTest {
            val shared = SharedGuestInit(composite = true)
            val guest = Recorder()

            shared.initialize(context("k" to "rule"), guest::init)
            shared.initialize(context("k" to "reporter"), guest::init)
            shared.reset()
            shared.initialize(context("other" to "x"), guest::init)

            assertEquals(JsonObject(mapOf("k" to JsonPrimitive("reporter"))), guest.configs[1])
            assertEquals(JsonObject(mapOf("other" to JsonPrimitive("x"))), guest.configs[2])
        }

    @Test
    fun `a single type guest is initialized as asked every time`() =
        runTest {
            val shared = SharedGuestInit(composite = false)
            val guest = Recorder()

            repeat(2) { shared.initialize(context("k" to "v"), guest::init) }

            assertEquals(2, guest.configs.size)
        }

    @Test
    fun `typed parts answer defaultEnabled and formatId for their own type`() {
        val transport = AbiTableTransportStub()
        val descriptor = BridgeDescriptor.parse("""{"id":"c","types":["REPORTER","RULE"]}""", "c", RecordingLogger())
        // A god bridge whose primary type resolves defaultEnabled to false, but whose REPORTER part is on.
        val guest =
            object : EnrichedReporterModule by BridgeEnrichedReporterModule(transport), PartAware {
                override val defaultEnabled = false

                override fun defaultEnabledFor(type: ModuleType): Boolean = type == ModuleType.REPORTER

                override fun formatIdFor(type: ModuleType): String = "fmt-$type"
            }

        val part = assertIs<ReporterModule>(typedPart(guest, ModuleType.REPORTER, descriptor, transport))

        assertTrue(part.defaultEnabled)
        assertEquals("fmt-REPORTER", part.formatId)
    }
}
