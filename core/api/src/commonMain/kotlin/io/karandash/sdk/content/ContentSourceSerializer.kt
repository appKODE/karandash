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
 * Custom serializer for [ContentSource] that auto-detects format from JSON structure.
 *
 * Decode rules:
 * - `JsonPrimitive` (string) → [ContentSource.Inline]
 * - `JsonObject` with `"inline"` key → [ContentSource.Inline] (explicit form)
 * - `JsonObject` with `"file"` key → [ContentSource.LocalFile]
 * - `JsonObject` with `"url"` key → [ContentSource.RemoteFile]
 * - `JsonObject` with `"repo-file"` key → [ContentSource.RepoFile]
 * - `JsonArray` → [ContentSource.Multiple]
 *
 * Encode rules:
 * - [ContentSource.Inline] → plain string primitive
 * - [ContentSource.LocalFile] → `{"file": "path", "auth"?: "name", "resolution"?: "..."}`
 * - [ContentSource.RemoteFile] → `{"url": "...", "auth"?: "name", "cache-ttl"?: N}`
 * - [ContentSource.RepoFile] → `{"repo-file": "path", "ref"?: "..."}`
 * - [ContentSource.Multiple] → JsonArray of serialized sources
 */
public object ContentSourceSerializer : KSerializer<ContentSource> {
    private const val KEY_INLINE = "inline"
    private const val KEY_FILE = "file"
    private const val KEY_URL = "url"
    private const val KEY_REPO_FILE = "repo-file"
    private const val KEY_AUTH = "auth"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_CACHE_TTL = "cache-ttl"
    private const val KEY_REF = "ref"

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.karandash.sdk.content.ContentSource")

    override fun deserialize(decoder: Decoder): ContentSource {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ContentSource can only be deserialized from JSON")
        return decodeFromElement(jsonDecoder.decodeJsonElement())
    }

    private fun decodeFromElement(element: JsonElement): ContentSource =
        when (element) {
            is JsonNull -> throw SerializationException("ContentSource cannot be null")
            is JsonArray -> decodeMultiple(element)
            is JsonObject -> decodeObject(element)
            is JsonPrimitive -> ContentSource.Inline(element.content)
        }

    private fun decodeMultiple(array: JsonArray): ContentSource.Multiple {
        val sources = array.map { decodeFromElement(it) }
        return ContentSource.Multiple(sources = sources)
    }

    private fun decodeObject(obj: JsonObject): ContentSource =
        when {
            obj.containsKey(KEY_INLINE) -> ContentSource.Inline(
                value = requireNotNull(obj[KEY_INLINE]) { "\"$KEY_INLINE\" value must not be null" }
                    .jsonPrimitive.content,
            )

            obj.containsKey(KEY_FILE) -> ContentSource.LocalFile(
                path = requireNotNull(obj[KEY_FILE]) { "\"$KEY_FILE\" value must not be null" }
                    .jsonPrimitive.content,
                auth = obj[KEY_AUTH]?.jsonPrimitive?.content,
                resolution = obj[KEY_RESOLUTION]?.jsonPrimitive?.content,
            )

            obj.containsKey(KEY_URL) -> ContentSource.RemoteFile(
                url = requireNotNull(obj[KEY_URL]) { "\"$KEY_URL\" value must not be null" }
                    .jsonPrimitive.content,
                auth = obj[KEY_AUTH]?.jsonPrimitive?.content,
                cacheTtl = obj[KEY_CACHE_TTL]?.let { decodeDuration(it) },
            )

            obj.containsKey(KEY_REPO_FILE) -> ContentSource.RepoFile(
                path = requireNotNull(obj[KEY_REPO_FILE]) { "\"$KEY_REPO_FILE\" value must not be null" }
                    .jsonPrimitive.content,
                ref = obj[KEY_REF]?.jsonPrimitive?.content,
            )

            else -> throw SerializationException(
                "Cannot decode ContentSource: object must contain one of: " +
                    "\"$KEY_INLINE\", \"$KEY_FILE\", \"$KEY_URL\", \"$KEY_REPO_FILE\""
            )
        }

    override fun serialize(
        encoder: Encoder,
        value: ContentSource
    ) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ContentSource can only be serialized to JSON")
        jsonEncoder.encodeJsonElement(encodeToElement(value))
    }

    private fun encodeToElement(source: ContentSource): JsonElement =
        when (source) {
            is ContentSource.Inline -> JsonPrimitive(source.value)
            is ContentSource.LocalFile -> encodeLocalFile(source)
            is ContentSource.RemoteFile -> encodeRemoteFile(source)
            is ContentSource.RepoFile -> encodeRepoFile(source)
            is ContentSource.Multiple -> encodeMultiple(source)
        }

    private fun encodeLocalFile(source: ContentSource.LocalFile): JsonObject =
        buildJsonObject {
            put(KEY_FILE, source.path)
            source.auth?.let { put(KEY_AUTH, it) }
            source.resolution?.let { put(KEY_RESOLUTION, it) }
        }

    private fun encodeRemoteFile(source: ContentSource.RemoteFile): JsonObject =
        buildJsonObject {
            put(KEY_URL, source.url)
            source.auth?.let { put(KEY_AUTH, it) }
            source.cacheTtl?.let { put(KEY_CACHE_TTL, it.toString()) }
        }

    private fun encodeRepoFile(source: ContentSource.RepoFile): JsonObject =
        buildJsonObject {
            put(KEY_REPO_FILE, source.path)
            source.ref?.let { put(KEY_REF, it) }
        }

    private fun encodeMultiple(source: ContentSource.Multiple): JsonArray = JsonArray(source.sources.map { encodeToElement(it) })

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
