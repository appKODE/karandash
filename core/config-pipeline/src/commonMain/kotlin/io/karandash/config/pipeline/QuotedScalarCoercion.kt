package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.config.schema.ConfigSections
import io.karandash.normalize.ConfigKeyNormalizer.kebabToCamelCase
import io.karandash.normalize.deepNormalizeKeys
import io.karandash.sdk.typed.TypedModule
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * a quoted number or boolean written literally in a typed rule's config (`minApprovals: "2"`) is still coerced
 * to the field's type, but now with a warning. A value that came from `${VAR}` interpolation is not a literal here
 * (the merged root is read before interpolation) and coerces silently.
 */
object QuotedScalarCoercion {
    private val NON_STRING_LITERAL = Regex("""[-+]?\d+(\.\d+)?|true|false""")

    suspend fun warnings(
        root: JsonElement,
        registry: ModuleRegistry,
    ): List<String> {
        val rules =
            (deepNormalizeKeys(root, ConfigMapParser.preserveKeysFor(registry)) as? JsonObject)?.get(ConfigSections.RULES) as? JsonObject
                ?: return emptyList()
        val typedByRule = registry
            .ruleModules()
            .flatMap { module ->
                val typed = module as? TypedModule<*> ?: return@flatMap emptyList()
                module.contributedRules().map { kebabToCamelCase(it) to typed }
            }.toMap()
        return rules.flatMap { (ruleId, config) ->
            val descriptor = typedByRule[kebabToCamelCase(ruleId)]?.configSerializer?.descriptor
            val fields = config as? JsonObject
            if (descriptor == null || fields == null) {
                emptyList()
            } else {
                fields.filter { (key, value) -> coercedLiteral(descriptor, key, value) }.keys.map {
                    "Config value 'rules.$ruleId.$it' is a quoted literal and is read as a non-string value; write it without quotes."
                }
            }
        }
    }

    private fun coercedLiteral(
        descriptor: SerialDescriptor,
        key: String,
        value: JsonElement,
    ): Boolean {
        val index = descriptor.getElementIndex(key)
        if (index < 0 || value !is JsonPrimitive || !value.isString || !NON_STRING_LITERAL.matches(value.content)) return false
        val kind = descriptor.getElementDescriptor(index).kind
        return kind is PrimitiveKind && kind != PrimitiveKind.STRING && kind != PrimitiveKind.CHAR
    }
}
