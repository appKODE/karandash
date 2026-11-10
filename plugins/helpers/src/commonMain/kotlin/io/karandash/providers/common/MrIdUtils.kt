package io.karandash.providers.common

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses [mrId] as a positive integer, throwing [IllegalArgumentException] if it is not a valid number.
 *
 * @param mrId The merge/pull request ID string from the caller.
 * @param label Human-readable label used in the error message (e.g. "PR number", "merge request ID").
 * @throws IllegalArgumentException if [mrId] cannot be parsed as an integer.
 */
fun requireMrIdAsInt(
    mrId: String,
    label: String = "merge request ID"
): Int =
    mrId.toIntOrNull()
        ?: throw IllegalArgumentException("Invalid $label: $mrId")

/**
 * The [field] of every object in [body], a JSON list, or in the list under [envelope] when the platform wraps lists
 * (`values` on Bitbucket, `value` on Azure DevOps). Used to read merge request ids out of a listing response;
 * entries without the field, or rejected by [where], are skipped.
 *
 * @throws IllegalStateException if [body] is not JSON.
 */
fun jsonListIds(
    body: String,
    field: String,
    envelope: String? = null,
    where: (JsonObject) -> Boolean = { true },
): List<String> {
    val root = try {
        KarandashJson.Lenient.parseToJsonElement(body)
    } catch (e: SerializationException) {
        throw IllegalStateException("Unreadable merge request list: ${e.message}", e)
    }
    check(root is JsonObject || root is JsonArray) { "Unreadable merge request list: not a JSON list" }
    val list = (if (envelope == null) root else (root as? JsonObject)?.get(envelope)) as? JsonArray ?: return emptyList()
    return list.filterIsInstance<JsonObject>().filter(where).mapNotNull { (it[field] as? JsonPrimitive)?.contentOrNull }
}
