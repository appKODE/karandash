package io.karandash.plugins.ai.providers

import io.karandash.models.support.KarandashJson
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpenAiStrictSchemaTest {
    private fun schema(json: String) = KarandashJson.Default.parseToJsonElement(json)

    @Test
    fun `a root that is not an object has no strict form`() {
        assertNull(OpenAiStrictSchema.of(schema("""{"type":"array","items":{"type":"string"}}""")))
        assertNull(OpenAiStrictSchema.of(schema("""{"type":"string"}""")))
    }

    @Test
    fun `an object root keeps its strict form with array and scalar properties`() {
        val root = schema(
            """{"type":"object","properties":{"tags":{"type":"array","items":{"type":"string"}},
               "n":{"type":"integer"}},"required":["tags"]}""",
        )

        assertNotNull(OpenAiStrictSchema.of(root))
    }
}
