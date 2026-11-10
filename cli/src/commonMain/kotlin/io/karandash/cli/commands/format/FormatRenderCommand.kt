package io.karandash.cli.commands.format

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.CliCommandBase
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.template.Mustache
import io.karandash.models.template.TemplateEscapes
import io.karandash.platform.readText
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Renders a report template file against the built-in example report (`format schema` prints its data), so a template
 * can be checked before it goes into a config. Unlike a configured template, an error is printed, not replaced by the
 * bundled layout.
 *
 * Usage:
 * ```
 * karandash format render --template summary.mustache --partial row=row.mustache --escape md
 * ```
 */
class FormatRenderCommand(
    bootstrap: PluginBootstrap = PluginBootstrap(),
) : CliCommandBase("render", bootstrap) {
    override fun help(context: Context) = "Render a report template against an example report"

    private val template by option("--template", help = "Path to a Mustache template file").required()
    private val partials by option("--partial", help = "A partial as name=file; repeatable").associate()
    private val escape by option("--escape", help = "How {{x}} is escaped: none, md (or markdown), html, slack or json")
        .choice(TemplateEscapes.BY_NAME, ignoreCase = true)
        .default(TemplateEscapes::none)

    override suspend fun execute(): Int {
        val files = mapOf("" to template) + partials
        val missing = files.values.filterNot { SystemFileSystem.exists(Path(it)) }
        if (missing.isNotEmpty()) {
            echo("File not found: ${missing.joinToString()}", err = true)
            return ExitCodes.CONFIG_ERROR
        }
        val texts = files.mapValues { (_, file) -> Path(file).readText() }
        return try {
            echo(Mustache.render(texts.getValue(""), exampleTemplateData(), texts - "", escape))
            ExitCodes.SUCCESS
        } catch (e: IllegalArgumentException) {
            echo("Template error: ${e.message}", err = true)
            ExitCodes.CONFIG_ERROR
        } catch (e: IllegalStateException) {
            e.rethrowCancellation()
            echo("Template error: ${e.message}", err = true)
            ExitCodes.CONFIG_ERROR
        }
    }
}
