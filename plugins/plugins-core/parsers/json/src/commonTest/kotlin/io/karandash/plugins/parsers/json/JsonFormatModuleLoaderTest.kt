package io.karandash.plugins.parsers.json

import io.karandash.plugins.parsers.createJsonFormatModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonFormatModuleLoaderTest {
    @Test
    fun `loader and factory expose the json parser under its registry id`() =
        runTest {
            val loader = JsonFormatModuleLoader()

            assertEquals("io.karandash.config-format.json", loader.id)
            assertEquals("json", createJsonFormatModule().formatId)
            assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1))), loader.parseToJsonElement("""{"a":1}"""))
        }
}
