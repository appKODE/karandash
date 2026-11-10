package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.config.AuthConfig
import io.karandash.config.schema.ConfigSections
import io.karandash.models.support.KarandashJson
import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * An `extends:` entry written `auth: <name>` takes its credentials, and the default of its `file-resolution`
 *, from the `auth.<name>` entry of the same config layer.
 */
internal object NamedExtendsAuth {
    /** The entry as written ([raw], for the empty-variable check) and decoded ([config]). */
    class Named(
        val raw: JsonObject,
        val config: AuthConfig,
    )

    fun resolve(
        name: String,
        layer: JsonObject,
        env: Map<String, String>,
        index: Int,
    ): Named {
        val where = "in extends object at index $index"
        val block = layer[ConfigSections.AUTH] as? JsonObject
        val raw = block?.get(name) as? JsonObject
            ?: throw ConfigException(
                "${KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode} Unknown auth '$name' $where: " +
                    "add it under the top-level 'auth:' block of the same file",
            )
        val entry = ConfigMapParser.deepInterpolateValues(raw, env) as JsonObject
        val config = try {
            KarandashJson.Default.decodeFromJsonElement<AuthConfig>(
                JsonObject(entry.mapKeys { (key, _) -> ConfigKeyNormalizer.kebabToCamelCase(key) }),
            )
        } catch (e: SerializationException) {
            // The message can quote a rejected value (a credential): name the entry only.
            throw ConfigException("${KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode} Invalid auth '$name' $where", e)
        }
        return Named(raw, config)
    }
}
