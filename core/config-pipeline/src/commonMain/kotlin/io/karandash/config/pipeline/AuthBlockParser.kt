package io.karandash.config.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.config.AuthConfig
import io.karandash.config.schema.ConfigSections
import io.karandash.content.AuthRegistry
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.manifest.RemoteFileResolution
import io.karandash.models.support.KarandashJson
import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Parses the top-level `auth:` block from a config [JsonElement] into an [AuthRegistry].
 *
 * The `auth:` block is a map of named [AuthConfig] entries used by [io.karandash.sdk.content.ContentSource]
 * resolution to supply credentials for remote and local file loading.
 *
 * Example config:
 * ```yaml
 * auth:
 *   my-gitlab:
 *     type: bearer
 *     token: ${GITLAB_TOKEN}
 * ```
 */
object AuthBlockParser {
    /**
     * Parse the `auth:` block from [element] and return an [AuthRegistry].
     *
     * Returns [AuthRegistry.EMPTY] when no `auth:` block is present or the block is empty.
     * Entries that fail to deserialize are skipped with a warn on [logger] naming the entry (never
     * its values, which hold credentials), so a malformed auth entry does not prevent the rest of
     * the config from loading.
     *
     * `${ENV_VAR}` placeholders in the block are interpolated (denylist enforced), with [envDefaults]
     * - the config's `env:` block - as fallbacks, so the raw bootstrap element can be passed directly.
     */
    fun parse(
        element: JsonElement,
        envDefaults: Map<String, String> = ConfigMapParser.parseEnvBlock(element).defaults,
        logger: Logger = NoOpLogger,
    ): AuthRegistry {
        val root = element as? JsonObject ?: return AuthRegistry.EMPTY
        val rawBlock = root[ConfigSections.AUTH] as? JsonObject ?: return AuthRegistry.EMPTY
        val authBlock = ConfigMapParser.deepInterpolateValues(rawBlock, envDefaults) as JsonObject

        val configs = buildMap<String, AuthConfig> {
            for ((name, value) in authBlock) {
                val obj = value as? JsonObject
                if (obj == null) {
                    logger.warn("Ignoring auth entry '$name': expected a map of settings")
                    continue
                }
                val config = try {
                    KarandashJson.Default.decodeFromJsonElement(
                        AuthConfig.serializer(),
                        JsonObject(
                            obj.mapKeys { (key, _) ->
                                ConfigKeyNormalizer.kebabToCamelCase(key)
                            }
                        )
                    )
                } catch (e: MissingFieldException) {
                    logger.warn("Ignoring auth entry '$name': missing ${e.missingFields.joinToString()}")
                    continue
                } catch (e: IllegalArgumentException) {
                    // SerializationException messages can quote the rejected value (a credential): log the keys only.
                    logger.warn("Ignoring auth entry '$name': invalid value among keys ${obj.keys.joinToString()} (${e::class.simpleName})")
                    continue
                }
                config.fileResolution?.let { value ->
                    if (RemoteFileResolution.fromString(value) == null) {
                        throw ConfigParseException(
                            "Invalid 'auth.$name.file-resolution' value '$value'",
                            hint = "Use 'local', 'fallback-remote', 'fallback-local' or 'strict'.",
                        )
                    }
                }
                put(name, config)
            }
        }

        return if (configs.isEmpty()) AuthRegistry.EMPTY else AuthRegistry(configs)
    }
}
