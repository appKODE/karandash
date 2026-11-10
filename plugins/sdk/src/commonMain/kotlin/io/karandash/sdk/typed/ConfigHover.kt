package io.karandash.sdk.typed

import io.karandash.api.config.ConfigField
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.config.configSchemaFromDescriptor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Markdown hover text for a typed rule, generated from the config class's [descriptor] so a key added to the
 * class shows up in the editor without touching a doc string. Nested classes are listed under their key, and a
 * key with a non-null default shows it (taken from [defaults], see [configDefaults]).
 */
internal fun configHoverMarkdown(
    ruleId: String,
    description: String,
    descriptor: SerialDescriptor,
    defaults: JsonObject = JsonObject(emptyMap()),
): String =
    buildString {
        appendLine("# $ruleId")
        appendLine()
        appendLine(description)
        appendLine()
        appendLine("## Properties")
        appendFields(configSchemaFromDescriptor(descriptor), defaults, indent = "")
    }.trimEnd()

/**
 * The values the config class takes when every key is omitted, as JSON; empty when the class has required keys
 * (nothing to default to) or its initializer rejects an empty config.
 */
internal fun <C : Any> configDefaults(serializer: KSerializer<C>): JsonObject {
    val descriptor = serializer.descriptor
    if ((0 until descriptor.elementsCount).any { !descriptor.isElementOptional(it) }) return JsonObject(emptyMap())
    val json = KarandashJson.Default
    return try {
        json.encodeToJsonElement(serializer, json.decodeFromJsonElement(serializer, JsonObject(emptyMap()))) as? JsonObject
    } catch (_: IllegalArgumentException) {
        // docs are best effort: a class that rejects an empty config just shows no defaults
        null
    } ?: JsonObject(emptyMap())
}

private fun StringBuilder.appendFields(
    fields: List<ConfigField>,
    defaults: JsonObject,
    indent: String,
) {
    for (field in fields) {
        val required = if (field.optional) "" else ", required"
        val default = defaults[field.name]?.takeUnless { it is JsonNull || field.nested != null }?.let { " - default: `$it`" }
        appendLine("$indent- `${field.name}` (${field.type.name.lowercase()}$required)${default.orEmpty()}")
        field.nested?.let { appendFields(it, defaults[field.name] as? JsonObject ?: JsonObject(emptyMap()), "$indent  ") }
    }
}
