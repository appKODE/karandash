package io.karandash.loader.bridge

import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.TranslationModule
import io.karandash.loader.RecordingLogger
import io.karandash.models.finding.PluralRule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A guest that does not implement a type's SDK interface yields no typed part for that type. */
class BridgeTypedPartTest {
    private class Guest(
        override val type: ModuleType,
    ) : Module {
        override val id: String = "guest"
        override val name: String = "Guest"
        override val version: String = "1.0"

        override suspend fun initialize(context: PluginContext): Boolean = true
    }

    private class Transport : AbiTableTransport("fake", RecordingLogger(), "Test") {
        override fun exports(op: BridgeOp): Boolean = false

        override fun invoke(
            op: BridgeOp,
            args: Array<out String>,
        ): String? = null
    }

    private val descriptor = BridgeDescriptor(JsonObject(emptyMap()), "guest")
    private val hostProxied = setOf(ModuleType.FORMAT, ModuleType.OUTPUT, ModuleType.PIPELINE_EXTENSION, ModuleType.TRANSLATION)

    @Test
    fun `only host proxied types get a part from a bare guest`() {
        ModuleType.entries.forEach { type ->
            val part = typedPart(Guest(type), type, descriptor, Transport())
            if (type in hostProxied) {
                assertEquals(type, assertNotNull(part, "$type").type)
            } else {
                assertNull(part, "$type")
            }
        }
    }

    @Test
    fun `a bare guest of an interface type falls back to a plain proxy`() {
        val module = bridgeFor(Guest(ModuleType.RULE), descriptor, Transport())
        assertEquals("guest", module.id)
        assertEquals(ModuleType.RULE, module.type)
    }

    @Test
    fun `a translation guest serves its pack from the descriptor`() {
        val pack = BridgeDescriptor(
            JsonObject(
                mapOf(
                    ModuleDescriptor.LANGUAGE to JsonPrimitive("pl"),
                    ModuleDescriptor.PLURAL_RULE to JsonPrimitive("WEST_SLAVIC"),
                    ModuleDescriptor.TRANSLATIONS to JsonObject(mapOf("Where" to JsonPrimitive("Gdzie"), "n" to JsonPrimitive(1))),
                ),
            ),
            "guest",
        )
        val module = assertIs<TranslationModule>(typedPart(Guest(ModuleType.TRANSLATION), ModuleType.TRANSLATION, pack, Transport()))
        assertEquals("pl", module.language)
        assertEquals(PluralRule.WEST_SLAVIC, module.pluralRule)
        assertEquals(mapOf("Where" to "Gdzie"), module.translations())
        val bare = assertIs<TranslationModule>(typedPart(Guest(ModuleType.TRANSLATION), ModuleType.TRANSLATION, descriptor, Transport()))
        assertEquals("", bare.language)
        assertEquals(PluralRule.ONE_OTHER, bare.pluralRule)
        assertEquals(emptyMap(), bare.translations())
    }
}
