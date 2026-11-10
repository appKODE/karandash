package io.karandash.config.pipeline

import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ConfigFormatModule
import io.karandash.manifest.ConfigSource
import io.karandash.sdk.config.anyToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** `Module.preserveKeys()` of a module (JS, WASM) keeps its map keys as written in the merged config. */
class ModulePreserveKeysTest {
    private val raw = linkedMapOf<String, Any?>("my-map" to linkedMapOf<String, Any?>("Some-Key" to "a"))

    private fun entry() = ChainEntry(ConfigSource("file://config.yaml"), depth = 0, mergeStrategy = null, content = "x", format = "yaml")

    private fun registry() =
        ConfigFormatRegistry(
            listOf(
                object : ConfigFormatModule {
                    override val formatId = "yaml"

                    override suspend fun parseToJsonElement(content: String): JsonElement = anyToJsonElement(raw)
                },
            ),
        )

    private fun JsonObject.mapKeys(): Set<String> = (this["myMap"] as JsonObject).keys

    @Test
    fun `chain parse camelCases the children of an unlisted map key`() =
        runTest {
            val merged = ConfigChainParser(registry()).parseAndMerge(ConfigChain(listOf(entry()))) as JsonObject

            assertEquals(setOf("SomeKey"), merged.mapKeys())
        }

    @Test
    fun `chain parse keeps the children of a module preserved key`() =
        runTest {
            val parser = ConfigChainParser(registry(), extraPreserveKeys = setOf("myMap"))
            val merged = parser.parseAndMerge(ConfigChain(listOf(entry()))) as JsonObject

            assertEquals(setOf("Some-Key"), merged.mapKeys())
        }

    @Test
    fun `full config parse keeps the children of a module preserved key`() {
        val element = anyToJsonElement(raw)
        val normalized = assertNotNull(
            ConfigMapParser
                .parseFullConfig(
                    element,
                    extensionKeys = setOf("myMap"),
                    extraPreserveKeys = setOf("myMap")
                ).config.extensions["myMap"],
        )

        assertEquals(setOf("Some-Key"), (normalized as JsonObject).keys)
    }

    @Test
    fun `registry preserve keys are the union of the modules keys`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(preservingModule("a", setOf("myMap")))
            registry.register(preservingModule("b", setOf("other")))

            assertEquals(setOf("myMap", "other"), ConfigMapParser.modulePreserveKeys(registry))
        }

    private fun preservingModule(
        moduleId: String,
        keys: Set<String>,
    ): Module =
        object : Module {
            override val id = moduleId
            override val name = moduleId
            override val version = "1"
            override val type = ModuleType.SCRIPT

            override suspend fun initialize(context: PluginContext): Boolean = true

            override fun preserveKeys(): Set<String> = keys
        }

    @Test
    fun `ai filter min-confidence-by-model keeps its model globs as written`() =
        runTest {
            val models = linkedMapOf<String, Any?>("anthropic/claude-haiku-*" to 0.4, "Open-AI/gpt_5" to 0.5)
            val ai = linkedMapOf<String, Any?>("filter" to linkedMapOf<String, Any?>("min-confidence-by-model" to models))
            val format = object : ConfigFormatModule {
                override val formatId = "yaml"

                override suspend fun parseToJsonElement(content: String): JsonElement =
                    anyToJsonElement(linkedMapOf<String, Any?>("ai" to ai))
            }

            val merged = ConfigChainParser(ConfigFormatRegistry(listOf(format))).parseAndMerge(ConfigChain(listOf(entry()))) as JsonObject

            val byModel = ((merged["ai"] as JsonObject)["filter"] as JsonObject)["minConfidenceByModel"] as JsonObject
            assertEquals(setOf("anthropic/claude-haiku-*", "Open-AI/gpt_5"), byModel.keys)
        }
}
