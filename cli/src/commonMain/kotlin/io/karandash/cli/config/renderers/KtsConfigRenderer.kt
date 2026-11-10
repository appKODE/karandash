package io.karandash.cli.config.renderers

import io.karandash.cli.templates.TemplateRenderer
import io.karandash.config.pipeline.ConfigRendererModule
import io.karandash.plugins.parsers.kts.KtsFormatModule
import kotlinx.serialization.json.JsonObject

/**
 * Renders configuration output in Kotlin DSL (.kts) format.
 *
 * Delegates full config rendering to [TemplateRenderer.renderToKotlinDsl].
 * Profile and wizard stubs produce hand-crafted KTS for readability.
 */
internal class KtsConfigRenderer : ConfigRendererModule {
    override val formatId: String = KtsFormatModule.FORMAT_ID
    override val defaultOutputPath: String = ".karandash/config.kts"

    override fun renderConfig(config: JsonObject): String = TemplateRenderer.renderToKotlinDsl(config)

    override fun renderProfileConfig(profileId: String): String = """profile("$profileId")"""

    override fun renderWizardConfig(
        profileId: String,
        platform: String,
        tokenVar: String
    ): String =
        buildString {
            appendLine("profile(\"$profileId\")")
            appendLine()
            appendLine("provider(\"$platform\") {")
            appendLine("    token = \"\${$tokenVar}\"")
            append("}")
        }
}
