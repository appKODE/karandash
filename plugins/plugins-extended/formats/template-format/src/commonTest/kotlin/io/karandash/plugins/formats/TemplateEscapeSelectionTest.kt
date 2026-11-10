package io.karandash.plugins.formats

import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestError
import io.karandash.testfixtures.createTestReport
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TemplateEscapeSelectionTest {
    private val report = createTestReport(listOf(createTestError(message = "a <b> & \"c\"", file = "src/App.kt", line = 3)))

    private suspend fun render(escape: String?): String {
        val module = TemplateFormatModule()
        module.initialize(TestPluginContext())
        val config = buildJsonObject {
            put("template", "{{#findings}}{{message}}{{/findings}}")
            if (escape != null) put("escape", escape)
        }
        return module.format(report, config).body
    }

    @Test
    fun `html escape encodes angle brackets and quotes`() =
        runTest {
            assertEquals("a &lt;b&gt; &amp; &quot;c&quot;", render("html"))
        }

    @Test
    fun `json escape quotes the string content`() =
        runTest {
            assertTrue(render("JSON").contains("\\\"c\\\""))
        }

    @Test
    fun `markdown slack and none escapes differ from the html one`() =
        runTest {
            val html = render("html")
            listOf("markdown", "md", "slack", "none", "").forEach { assertNotEquals(html, render(it), it) }
        }

    @Test
    fun `russian text renders in the report language with html escaping intact`() =
        runTest {
            RussianTranslationModule.register()
            val module = TemplateFormatModule()
            module.initialize(TestPluginContext())
            val russian = createTestReport(listOf(createTestError(message = "Метод <init> & «поле»", file = "src/App.kt", line = 3)))
            val config = buildJsonObject {
                put("template", "{{headline}}|{{#findings}}{{message}}{{/findings}}")
                put("escape", "html")
                put("language", "ru")
            }
            assertEquals("Не пройдено · 1 ошибка|Метод &lt;init&gt; &amp; «поле»", module.format(russian, config).body)
        }

    @Test
    fun `an unknown escape warns and falls back to none`() =
        runTest {
            val module = TemplateFormatModule()
            val context = TestPluginContext()
            module.initialize(context)
            val config = buildJsonObject {
                put("template", "{{#findings}}{{message}}{{/findings}}")
                put("escape", "rot13")
            }
            assertEquals("a <b> & \"c\"", module.format(report, config).body)
            assertTrue(context.warnings.any { "unknown escape" in it }, "${context.warnings}")
        }
}
