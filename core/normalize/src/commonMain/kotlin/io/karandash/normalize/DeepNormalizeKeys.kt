package io.karandash.normalize

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Recursively normalizes all JSON object keys to camelCase at all depths,
 * except for keys whose parent key is in [preserveKeys] - those children's
 * keys are preserved as-is (user-defined identifiers).
 *
 * @param element The JSON element to normalize.
 * @param preserveKeys Set of camelCase key names whose map children should
 *   NOT have their keys normalized (e.g., "headers", "patterns", "auth", "env").
 */
fun deepNormalizeKeys(
    element: JsonElement,
    preserveKeys: Set<String> = emptySet(),
): JsonElement = deepNormalizeKeys(element, preserveKeys) { _, _ -> }

/**
 * Same as the two-argument form, and reports colliding keys through [onCollision].
 *
 * @param onCollision Called when several keys of one object normalize to the same name; the last one wins.
 *   Receives the normalized key and the original spellings in document order.
 */
fun deepNormalizeKeys(
    element: JsonElement,
    preserveKeys: Set<String> = emptySet(),
    onCollision: (normalizedKey: String, originalKeys: List<String>) -> Unit,
): JsonElement =
    when (element) {
        is JsonObject -> {
            element.keys
                .groupBy { ConfigKeyNormalizer.kebabToCamelCase(it) }
                .forEach { (normalized, originals) -> if (originals.size > 1) onCollision(normalized, originals) }
            JsonObject(
                element.entries.associate { (key, value) ->
                    val normalizedKey = ConfigKeyNormalizer.kebabToCamelCase(key)
                    val normalizedValue = if (normalizedKey in preserveKeys) {
                        preserveChildKeys(value, preserveKeys, onCollision)
                    } else {
                        deepNormalizeKeys(value, preserveKeys, onCollision)
                    }
                    normalizedKey to normalizedValue
                },
            )
        }

        is JsonArray -> {
            JsonArray(element.map { deepNormalizeKeys(it, preserveKeys, onCollision) })
        }

        else -> {
            element
        }
    }

/**
 * Preserves immediate child keys (user-defined) but continues normalizing
 * deeper nested schema keys within each child's value.
 */
private fun preserveChildKeys(
    element: JsonElement,
    preserveKeys: Set<String>,
    onCollision: (String, List<String>) -> Unit,
): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(
            element.entries.associate { (key, value) ->
                key to deepNormalizeKeys(value, preserveKeys, onCollision)
            },
        )

        is JsonArray -> JsonArray(element.map { preserveChildKeys(it, preserveKeys, onCollision) })

        else -> element
    }

/** Warning text for an [deepNormalizeKeys] collision: the spellings that merged and which one survives. */
fun normalizedKeyCollisionMessage(
    normalizedKey: String,
    originalKeys: List<String>,
): String = "Config keys ${originalKeys.joinToString(", ") { "'$it'" }} all normalize to '$normalizedKey'; the last one wins"
