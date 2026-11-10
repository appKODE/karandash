package com.example.karandash

import io.karandash.api.InternalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExampleConfigFormatPluginTest {
    private val format = ExamplePropertiesFormat()

    @OptIn(InternalKarandashApi::class)
    @Test
    fun `ServiceLoader-discovered plugin is exposed through configFormats`() =
        runTest {
            val registry = ModuleRegistryImpl()
            ServiceLoader.load(Module::class.java).forEach { registry.register(it) }

            val formats = registry.configFormats()

            assertEquals(listOf("example-properties"), formats.map { it.formatId })
            assertEquals(setOf("properties"), formats.single().fileExtensions)
        }

    @Test
    fun `parses key=value lines and skips comments`() =
        runTest {
            val content = "# comment\nprofile = example-team\n\nnot a pair\nextends=base.yaml\n"

            assertEquals(
                JsonObject(mapOf("profile" to JsonPrimitive("example-team"), "extends" to JsonPrimitive("base.yaml"))),
                format.parseToJsonElement(content),
            )
        }

    @Test
    fun `parseExtendsOnly reads extends without full parsing`() =
        runTest {
            assertEquals(JsonPrimitive("base.yaml"), format.parseExtendsOnly("a=b\nextends=base.yaml"))
            assertEquals(JsonNull, format.parseExtendsOnly("extends=null"))
            assertNull(format.parseExtendsOnly("a=b"))
        }
}
