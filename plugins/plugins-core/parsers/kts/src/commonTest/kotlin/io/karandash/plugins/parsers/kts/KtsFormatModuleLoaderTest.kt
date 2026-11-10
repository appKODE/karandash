package io.karandash.plugins.parsers.kts

import io.karandash.api.ExperimentalKarandashApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalKarandashApi::class)
class KtsFormatModuleLoaderTest {
    @Test
    fun `loader exposes the kts parser under its registry id and priority`() {
        val loader = KtsFormatModuleLoader()

        assertEquals("io.karandash.config-format.kts", loader.id)
        assertEquals(KtsFormatModule.PRIORITY, loader.priority)
    }

    @Test
    fun `non object rule schemas are ignored`() =
        runTest {
            val module = KtsFormatModule().withRuleSchemas(JsonObject(mapOf("titleFormat" to JsonPrimitive("bogus"))))

            val rules = module.parseToJsonElement("rules {\n    titleFormat {\n        pattern = \"x\"\n    }\n}").jsonObject

            assertEquals(
                JsonPrimitive("x"),
                rules
                    .getValue("rules")
                    .jsonObject
                    .getValue("titleFormat")
                    .jsonObject["pattern"]
            )
        }
}
