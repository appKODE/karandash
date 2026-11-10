package io.karandash.cli.config.renderers

import io.karandash.cli.templates.TemplateRenderer
import io.karandash.config.pipeline.ConfigRendererModule
import io.karandash.plugins.parsers.json.JsonFormatModule
import kotlinx.serialization.json.JsonObject

/**
 * Renders configuration output in JSON format.
 *
 * Delegates full config rendering to [TemplateRenderer.renderToJson].
 * Profile and wizard stubs produce hand-crafted JSON for readability.
 */
internal class JsonConfigRenderer : ConfigRendererModule {
    override val formatId: String = JsonFormatModule.FORMAT_ID
    override val defaultOutputPath: String = ".karandash/config.json"

    override fun renderConfig(config: JsonObject): String = TemplateRenderer.renderToJson(config)

    override fun renderProfileConfig(profileId: String): String = """{"profile": "$profileId"}"""

    override fun renderWizardConfig(
        profileId: String,
        platform: String,
        tokenVar: String
    ): String =
        buildString {
            appendLine("{")
            appendLine("  \"profile\": \"$profileId\",")
            appendLine("  \"providers\": {")
            appendLine("    \"$platform\": {")
            appendLine("      \"token\": \"\${$tokenVar}\"")
            appendLine("    }")
            appendLine("  }")
            append("}")
        }
}
