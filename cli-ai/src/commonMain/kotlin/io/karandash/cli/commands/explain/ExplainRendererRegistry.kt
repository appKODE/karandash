package io.karandash.cli.commands.explain

/**
 * Registry of available explain output renderers.
 *
 * Maps format identifiers (e.g. "text", "markdown", "json", "doc") to render functions.
 * Falls back to [TextExplainRenderer] for unknown format values.
 */
internal object ExplainRendererRegistry {
    private val renderers: Map<String, (ExplainResult) -> String> = mapOf(
        "text" to TextExplainRenderer::render,
        "markdown" to MarkdownExplainRenderer::render,
        "json" to JsonExplainRenderer::render,
        "doc" to DocExplainRenderer::render,
    )

    /**
     * Render an [ExplainResult] using the specified format.
     *
     * @param format The format identifier (e.g. "text", "markdown").
     * @param result The explain result to render.
     * @return Rendered output string.
     */
    fun render(
        format: String,
        result: ExplainResult
    ): String = (renderers[format] ?: renderers.getValue("text")).invoke(result)

    /**
     * @return The set of supported format identifiers.
     */
    fun supportedFormats(): Set<String> = renderers.keys
}
