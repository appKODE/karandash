package io.karandash.cli.config.renderers

import io.karandash.api.support.mapToJsonElement
import io.karandash.cli.templates.TemplateRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonConfigRendererTest {
    private val renderer = JsonConfigRenderer()

    @Test
    fun `formatId is json`() {
        assertEquals("json", renderer.formatId)
    }

    @Test
    fun `defaultOutputPath is correct`() {
        assertEquals(".karandash/config.json", renderer.defaultOutputPath)
    }

    @Test
    fun `renderConfig delegates to TemplateRenderer`() {
        val config = mapOf("version" to "1" as Any, "rules" to mapOf("title-format" to mapOf("enabled" to true)) as Any)
        val expected = TemplateRenderer.renderToJson(mapToJsonElement(config))
        assertEquals(expected, renderer.renderConfig(mapToJsonElement(config)))
    }

    @Test
    fun `renderProfileConfig produces valid JSON`() {
        val result = renderer.renderProfileConfig("conventional-commits")
        assertEquals("""{"profile": "conventional-commits"}""", result)
    }

    @Test
    fun `renderWizardConfig produces valid JSON with provider block`() {
        val result = renderer.renderWizardConfig("minimal", "gitlab", "GITLAB_TOKEN")
        assertTrue(result.startsWith("{"))
        assertTrue(result.endsWith("}"))
        assertTrue(result.contains("\"profile\": \"minimal\""))
        assertTrue(result.contains("\"providers\""))
        assertTrue(result.contains("\"gitlab\""))
        assertTrue(result.contains("\"\${GITLAB_TOKEN}\""))
    }
}
