package io.karandash.cli.config.renderers

import io.karandash.cli.templates.TemplateRenderer
import io.karandash.config.pipeline.ConfigRendererModule
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.serialization.json.JsonObject

/**
 * Renders configuration output in YAML format.
 *
 * Delegates full config rendering to [TemplateRenderer.renderToYaml].
 * Profile and wizard stubs produce hand-crafted YAML for readability.
 */
internal class YamlConfigRenderer : ConfigRendererModule {
    override val formatId: String = YamlFormatModule.FORMAT_ID
    override val defaultOutputPath: String = ".karandash/config.yaml"

    override fun renderConfig(config: JsonObject): String = TemplateRenderer.renderToYaml(config)

    override fun renderProfileConfig(profileId: String): String = "profile: $profileId\n"

    override fun renderWizardConfig(
        profileId: String,
        platform: String,
        tokenVar: String
    ): String =
        buildString {
            appendLine("profile: $profileId")
            appendLine()
            appendLine("platforms:")
            appendLine("  $platform:")
            append("    token: \"\${$tokenVar}\"")
        }
}
