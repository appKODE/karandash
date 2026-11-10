package io.karandash.api.module

import kotlinx.serialization.Serializable

/**
 * Human-readable documentation for a module, used by the LSP hover provider.
 *
 * @property summary Markdown summary of the module's purpose and behavior.
 * @property configKeyDocs Map of config property name to its documentation string.
 * @property examples Example config snippets (markdown code blocks).
 */
@Serializable
public data class ModuleDocumentation(
    val summary: String,
    val configKeyDocs: Map<String, String> = emptyMap(),
    val examples: List<String> = emptyList(),
)
