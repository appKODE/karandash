package io.karandash.plugins.ai.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiReporterSectionTest {
    private val finding = Finding(Severity.ERROR, "Injection", "sql", location = Location.FileRef("db/Dao.kt", 42))

    private fun section(transforms: JsonObject) =
        AiReporterSection.from(buildJsonObject { putJsonObject("reporters") { put("findingTransform", transforms) } })

    @Test
    fun `finding transforms render each named template`() {
        val section = section(
            buildJsonObject {
                putJsonArray("transforms") {
                    addJsonObject {
                        put("name", "slack")
                        put("template", "*{{severity}}* {{rule}} {{file}}:{{line}}")
                    }
                    addJsonObject { put("name", "no-template") }
                    add("not-an-object")
                }
            },
        )

        assertEquals(setOf("transform-slack"), section.reportIds)
        assertEquals(mapOf("transform-slack" to "*ERROR* sql db/Dao.kt:42"), section.generate(listOf(finding)))
    }

    @Test
    fun `unknown and disabled reporters are ignored`() {
        val section = AiReporterSection.from(
            buildJsonObject {
                putJsonObject("reporters") {
                    put("junit", true)
                    putJsonObject("smart-digest") { put("enabled", false) }
                }
            },
        )

        assertTrue(section.reportIds.isEmpty())
        assertTrue(section.generate(listOf(finding)).isEmpty())
    }

    @Test
    fun `missing reporters block gives an empty section`() {
        assertTrue(AiReporterSection.from(null).reportIds.isEmpty())
    }
}
