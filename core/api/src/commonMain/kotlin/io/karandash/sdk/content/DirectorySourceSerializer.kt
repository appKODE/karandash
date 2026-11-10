package io.karandash.sdk.content

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Custom serializer for [DirectorySource] that auto-detects format from JSON structure.
 *
 * Decode rules:
 * - `JsonPrimitive` (string) → [DirectorySource.LocalDir]
 * - `JsonObject` with `"path"` key → [DirectorySource.LocalDir]
 * - `JsonObject` with `"url"` key → [DirectorySource.RemoteDir]
 * - `JsonObject` with `"repo-dir"` key → [DirectorySource.RepoDir]
 *
 * Encode rules:
 * - [DirectorySource.LocalDir] → plain string primitive
 * - [DirectorySource.RemoteDir] → `{"url": "...", "auth"?: "name", "cache-ttl"?: N}`
 * - [DirectorySource.RepoDir] → `{"repo-dir": "path", "ref"?: "..."}`
 */
public object DirectorySourceSerializer : KSerializer<DirectorySource> {
    private const val KEY_URL = "url"
    private const val KEY_REPO_DIR = "repo-dir"
    private const val KEY_PATH = "path"
    private const val KEY_AUTH = "auth"
    private const val KEY_CACHE_TTL = "cache-ttl"
    private const val KEY_REF = "ref"

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.karandash.sdk.content.DirectorySource")

    override fun deserialize(decoder: Decoder): DirectorySource {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("DirectorySource can only be deserialized from JSON")
        return decodeFromElement(jsonDecoder.decodeJsonElement())
    }

    private fun decodeFromElement(element: JsonElement): DirectorySource =
        when (element) {
            is JsonNull -> throw SerializationException("DirectorySource cannot be null")
            is JsonObject -> decodeObject(element)
            is JsonArray -> throw SerializationException("DirectorySource cannot be an array")
            is JsonPrimitive -> DirectorySource.LocalDir(element.content)
        }

    private fun decodeObject(obj: JsonObject): DirectorySource =
        when {
            obj.containsKey(KEY_URL) -> DirectorySource.RemoteDir(
                url = requireNotNull(obj[KEY_URL]) { "\"$KEY_URL\" value must not be null" }
                    .jsonPrimitive.content,
                auth = obj[KEY_AUTH]?.jsonPrimitive?.content,
                cacheTtl = obj[KEY_CACHE_TTL]?.let { decodeDuration(it) },
            )

            obj.containsKey(KEY_REPO_DIR) -> DirectorySource.RepoDir(
                path = requireNotNull(obj[KEY_REPO_DIR]) { "\"$KEY_REPO_DIR\" value must not be null" }
                    .jsonPrimitive.content,
                ref = obj[KEY_REF]?.jsonPrimitive?.content,
            )

            obj.containsKey(KEY_PATH) -> DirectorySource.LocalDir(
                path = requireNotNull(obj[KEY_PATH]) { "\"$KEY_PATH\" value must not be null" }
                    .jsonPrimitive.content,
            )

            else -> throw SerializationException(
                "Cannot decode DirectorySource: object must contain one of: " +
                    "\"$KEY_PATH\", \"$KEY_URL\", \"$KEY_REPO_DIR\""
            )
        }

    override fun serialize(
        encoder: Encoder,
        value: DirectorySource
    ) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("DirectorySource can only be serialized to JSON")
        jsonEncoder.encodeJsonElement(encodeToElement(value))
    }

    private fun encodeToElement(source: DirectorySource): JsonElement =
        when (source) {
            is DirectorySource.LocalDir -> JsonPrimitive(source.path)
            is DirectorySource.RemoteDir -> encodeRemoteDir(source)
            is DirectorySource.RepoDir -> encodeRepoDir(source)
        }

    private fun encodeRemoteDir(source: DirectorySource.RemoteDir): JsonObject =
        buildJsonObject {
            put(KEY_URL, source.url)
            source.auth?.let { put(KEY_AUTH, it) }
            source.cacheTtl?.let { put(KEY_CACHE_TTL, it.toString()) }
        }

    private fun encodeRepoDir(source: DirectorySource.RepoDir): JsonObject =
        buildJsonObject {
            put(KEY_REPO_DIR, source.path)
            source.ref?.let { put(KEY_REF, it) }
        }

    private fun decodeDuration(element: JsonElement): io.karandash.duration.KarandashDuration {
        val text = element.jsonPrimitive.content
        val asLong = text.toLongOrNull()
        return if (asLong != null) {
            io.karandash.duration.KarandashDuration
                .ofSeconds(asLong)
        } else {
            io.karandash.duration.KarandashDuration
                .parse(text)
        }
    }
}
