package io.karandash.plugins.ai.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckRunAnnotationReportTest {
    private val findings = listOf(
        Finding(Severity.ERROR, "Injection", "sql", location = Location.FileRef("db/Dao.kt", 42)),
        Finding(Severity.WARNING, "Long line", "style", location = Location.FileRef("db/Dao.kt", 42)),
        Finding(Severity.INFO, "No location", "title"),
    )

    @Test
    fun `github format lists file findings as annotations`() {
        val json = Json.parseToJsonElement(CheckRunAnnotationReport.render(findings, JsonObject(emptyMap()))).jsonObject

        assertEquals("failure", json["conclusion"]?.jsonPrimitive?.content)
        val annotations = json.getValue("annotations").jsonArray
        assertEquals(2, annotations.size)
        assertEquals("failure", annotations[0].jsonObject["annotation_level"]?.jsonPrimitive?.content)
        assertEquals("42", annotations[0].jsonObject["start_line"]?.jsonPrimitive?.content)
    }

    @Test
    fun `github title and summary follow the report language`() {
        RussianTranslationModule.register()
        val ru = Json.parseToJsonElement(CheckRunAnnotationReport.render(findings, JsonObject(emptyMap()), "ru")).jsonObject
        assertEquals("Проверка Karandash", ru["title"]?.jsonPrimitive?.content)
        assertEquals("ошибок: 1, предупреждений: 1, аннотаций: 2", ru["summary"]?.jsonPrimitive?.content)
        assertEquals("failure", ru["conclusion"]?.jsonPrimitive?.content)
        val en = Json.parseToJsonElement(CheckRunAnnotationReport.render(findings, JsonObject(emptyMap()))).jsonObject
        assertEquals("Karandash Validation", en["title"]?.jsonPrimitive?.content)
        assertEquals("1 errors, 1 warnings, 2 annotations", en["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `consolidate merges findings on the same line`() {
        val options = JsonObject(mapOf("consolidate" to JsonPrimitive(true)))
        val annotations = Json
            .parseToJsonElement(
                CheckRunAnnotationReport.render(findings, options)
            ).jsonObject
            .getValue("annotations")
            .jsonArray

        assertEquals(1, annotations.size)
        assertEquals("sql, style", annotations[0].jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals("failure", annotations[0].jsonObject["annotation_level"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gitlab format renders code quality entries`() {
        val options = JsonObject(mapOf("format" to JsonPrimitive("gitlab")))
        val entries = Json.parseToJsonElement(CheckRunAnnotationReport.render(findings, options)).jsonArray

        assertEquals(2, entries.size)
        assertEquals("major", entries[0].jsonObject["severity"]?.jsonPrimitive?.content)
        assertEquals(
            "db/Dao.kt",
            entries[0]
                .jsonObject
                .getValue("location")
                .jsonObject["path"]
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `no findings gives an empty annotation list`() {
        val json = Json.parseToJsonElement(CheckRunAnnotationReport.render(emptyList(), JsonObject(emptyMap()))).jsonObject

        assertEquals(0, json.getValue("annotations").jsonArray.size)
        assertEquals("success", json["conclusion"]?.jsonPrimitive?.content)
    }
}
