package io.karandash.lsp

import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.lsp.ModuleDocRegistry.displayName

/**
 * Formats hover documentation as Markdown for the LSP hover response.
 */
object HoverMarkdownFormatter {
    /**
     * Builds a Markdown hover doc for a single config property.
     */
    fun formatConfigProperty(prop: ConfigProperty): String = formatConfigProperty(prop.name, prop.type, prop.description)

    /**
     * Builds a Markdown hover doc for a config property by its components.
     */
    fun formatConfigProperty(
        name: String,
        type: ConfigPropertyType,
        description: String,
    ): String =
        """
        # $name

        **$description**

        **Type:** `${type.displayName()}`
        """.trimIndent()
}
