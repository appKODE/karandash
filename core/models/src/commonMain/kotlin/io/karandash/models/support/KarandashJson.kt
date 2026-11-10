package io.karandash.models.support

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Shared [Json] instances for the Karandash codebase.
 *
 * All serialization must use one of these - never create inline `Json {}` blocks.
 *
 * - [Default] - lenient: ignores unknown keys, coerces input values. Suitable for
 *   parsing external data (provider responses, config files, plugin output).
 * - [Strict] - strict: rejects unknown keys. Suitable for internal serialization
 *   where the schema is fully controlled.
 *
 * Narrower presets, each matching the flags of the inline blocks it replaced so behavior is unchanged:
 * - [Plain] - kotlinx defaults (compact, omits defaults, rejects unknown keys).
 * - [Pretty] - kotlinx defaults with pretty printing (human-facing CLI/file output).
 * - [IgnoreUnknown] - kotlinx defaults but tolerates unknown keys (persisted state files).
 * - [Wire] - ignores unknown keys, encodes defaults. Use on BOTH sides of a wire protocol
 *   (JAR bridge client/server, script subprocess) so the two ends cannot drift.
 * - [Lenient] - ignores unknown keys, lenient parsing, encodes defaults (HTTP provider responses).
 * - [PrettyWithDefaults] - pretty printing, encodes defaults (reporter JSON output).
 * - [PrettyIgnoreUnknown] - pretty printing, tolerates unknown keys (key index files).
 * - [ConfigEncoder] - encodes defaults, omits nulls (config data class to `JsonElement`).
 * - [ConfigDecoder] - the same instance as [Default] (config `JsonElement` to data class).
 * - [RuleConfig] - [Default] without coercion: a rule config block that does not fit its class fails to decode.
 * - [Report] - [Default] plus structured map keys (`ValidationReport`).
 * - [ConfigFile] - [Lenient] plus line and block comments and trailing commas (`.karandash/config.json`).
 */
object KarandashJson {
    /** Enum values decode case-insensitively (`PARENS` and `parens` both match `@SerialName("parens")`). */
    @OptIn(ExperimentalSerializationApi::class)
    val Default: Json = Json {
        decodeEnumsCaseInsensitive = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    val Strict: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    val Plain: Json = Json

    val Pretty: Json = Json {
        prettyPrint = true
    }

    val IgnoreUnknown: Json = Json {
        ignoreUnknownKeys = true
    }

    val Wire: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val Lenient: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    val PrettyWithDefaults: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    val PrettyIgnoreUnknown: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    val ConfigEncoder: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** Same decoding as [Default]; kept as a name for the config decoders (the duplicate preset was merged). */
    val ConfigDecoder: Json = Default

    /**
     * Decodes a rule's config block at load. Accepts what real configs carry: lowercase enum
     * values and numbers given as strings (`"${'$'}{KARANDASH_AI_MAX_TOKENS}"`), and ignores unknown keys (they are
     * warned about separately). Unlike [Default] it does not coerce: a value of the wrong type, an unknown enum
     * value or a null for a non-null field fails instead of falling back to the field's default.
     */
    @OptIn(ExperimentalSerializationApi::class)
    val RuleConfig: Json = Json {
        decodeEnumsCaseInsensitive = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * [Default] plus structured map keys, for types with non-primitive map keys such as
     * `ValidationReport.groupedByLocation` (encoded as a `[key1, value1, key2, value2, …]` array).
     */
    val Report: Json = Json(Default) {
        allowStructuredMapKeys = true
    }

    /**
     * Reads hand-written JSON config files: [Lenient] plus line and block comments and trailing commas, so a
     * `config.json` accepts what a `config.yaml` user writes out of habit. Used only by the JSON config format.
     */
    @OptIn(ExperimentalSerializationApi::class)
    val ConfigFile: Json = Json(Lenient) {
        allowComments = true
        allowTrailingComma = true
    }
}
