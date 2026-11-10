package io.karandash.lsp

import io.karandash.api.config.ConfigPropertyType

/**
 * Static fallback hover documentation for built-in modules.
 *
 * The [HoverProvider] checks [io.karandash.api.Module.hoverDocumentation] first; if that returns
 * null it falls back to the maps here. This object is a pure data file - there is no class-size
 * limit concern.
 */
object ModuleDocRegistry {
    val FALLBACK_VALIDATOR_DOCS: Map<String, String> get() = ValidatorDocs.FALLBACK_VALIDATOR_DOCS

    val FALLBACK_PROPERTY_DOCS: Map<String, String> get() = PropertyDocs.FALLBACK_PROPERTY_DOCS

    val TOP_LEVEL_BLOCK_DOCS: Map<String, String> get() = TopLevelBlockDocs.TOP_LEVEL_BLOCK_DOCS

    /** Hover docs for properties inside a `policy { }` entry. */
    val POLICY_PROPERTY_DOCS: Map<String, String> get() = PolicyDocs.POLICY_PROPERTY_DOCS

    /** Maps [ConfigPropertyType] to a human-readable display name. */
    fun ConfigPropertyType.displayName(): String =
        when (this) {
            ConfigPropertyType.STRING -> "String"
            ConfigPropertyType.INT -> "Int"
            ConfigPropertyType.BOOLEAN -> "Boolean"
            ConfigPropertyType.STRING_LIST -> "List<String>"
            ConfigPropertyType.OBJECT -> "Object"
            ConfigPropertyType.OBJECT_LIST -> "List<Object>"
            ConfigPropertyType.ENUM -> "Enum"
        }
}
