package io.karandash.cli.config.renderers

import io.karandash.api.support.mapToJsonElement
import io.karandash.cli.templates.TemplateRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KtsConfigRendererTest {
    private val renderer = KtsConfigRenderer()

    @Test
    fun `formatId is kts`() {
        assertEquals("kts", renderer.formatId)
    }

    @Test
    fun `defaultOutputPath is correct`() {
        assertEquals(".karandash/config.kts", renderer.defaultOutputPath)
    }

    @Test
    fun `renderConfig delegates to TemplateRenderer`() {
        val config = mapOf("version" to "1" as Any, "rules" to mapOf("title-format" to mapOf("enabled" to true)) as Any)
        val expected = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))
        assertEquals(expected, renderer.renderConfig(mapToJsonElement(config)))
    }

    @Test
    fun `renderProfileConfig produces valid KTS`() {
        val result = renderer.renderProfileConfig("conventional-commits")
        assertEquals("""profile("conventional-commits")""", result)
    }

    @Test
    fun `renderWizardConfig produces valid KTS with provider block`() {
        val result = renderer.renderWizardConfig("minimal", "github", "GITHUB_TOKEN")
        assertTrue(result.contains("profile(\"minimal\")"))
        assertTrue(result.contains("provider(\"github\")"))
        assertTrue(result.contains("token = \"\${GITHUB_TOKEN}\""))
    }
}
