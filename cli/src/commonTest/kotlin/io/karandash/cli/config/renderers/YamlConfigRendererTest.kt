package io.karandash.cli.config.renderers

import io.karandash.api.support.mapToJsonElement
import io.karandash.cli.templates.TemplateRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YamlConfigRendererTest {
    private val renderer = YamlConfigRenderer()

    @Test
    fun `formatId is yaml`() {
        assertEquals("yaml", renderer.formatId)
    }

    @Test
    fun `defaultOutputPath is correct`() {
        assertEquals(".karandash/config.yaml", renderer.defaultOutputPath)
    }

    @Test
    fun `renderConfig delegates to TemplateRenderer`() {
        val config = mapOf("version" to "1" as Any, "rules" to mapOf("title-format" to mapOf("enabled" to true)) as Any)
        val expected = TemplateRenderer.renderToYaml(mapToJsonElement(config))
        assertEquals(expected, renderer.renderConfig(mapToJsonElement(config)))
    }

    @Test
    fun `renderProfileConfig produces valid YAML`() {
        val result = renderer.renderProfileConfig("conventional-commits")
        assertEquals("profile: conventional-commits\n", result)
    }

    @Test
    fun `renderWizardConfig produces profile and provider blocks`() {
        val result = renderer.renderWizardConfig("minimal", "github", "GITHUB_TOKEN")
        assertTrue(result.contains("profile: minimal"))
        assertTrue(result.contains("platforms:"))
        assertTrue(result.contains("  github:"))
        assertTrue(result.contains("    token: \"\${GITHUB_TOKEN}\""))
    }
}
