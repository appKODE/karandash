package io.karandash.plugins.parsers.yaml

import io.karandash.api.errors.KarandashError
import io.karandash.api.module.ConfigFormatModule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.mamoe.yamlkt.Yaml
import net.mamoe.yamlkt.YamlDecodingException
import net.mamoe.yamlkt.YamlElement
import net.mamoe.yamlkt.YamlList
import net.mamoe.yamlkt.YamlLiteral
import net.mamoe.yamlkt.YamlMap
import net.mamoe.yamlkt.YamlNull

/**
 * Multiplatform YAML config format module using yamlkt.
 * Converts YAML content to a [JsonElement] tree for further processing.
 */
class YamlFormatModule : ConfigFormatModule {
    companion object {
        const val FORMAT_ID = "yaml"
        const val PRIORITY = 10

        /**
         * Matches structurally numeric doubles: digits with optional decimal and exponent.
         * Prevents Kotlin's `toDoubleOrNull()` from treating strings like `"365d"` as
         * valid doubles (`d` is a Kotlin double literal suffix on JVM).
         */
        private val STRUCTURAL_DOUBLE = Regex("""^-?\d+(\.\d+)?([eE][+-]?\d+)?$""")
    }

    override val formatId: String = FORMAT_ID
    override val fileExtensions: Set<String> = setOf("yaml", "yml")
    override val priority: Int = PRIORITY

    /**
     * Parse YAML content into a [JsonElement] tree.
     *
     * @param content The YAML content as a string.
     * @return [JsonElement] representation of the YAML content.
     * @throws IllegalArgumentException if YAML is syntactically invalid.
     */
    override suspend fun parseToJsonElement(content: String): JsonElement {
        if (content.isBlank() || isEmptyYaml(content)) return JsonObject(emptyMap())
        return try {
            parseYamlToJsonElement(ColonScalarQuoter.quote(content))
        } catch (e: YamlDecodingException) {
            throw IllegalArgumentException("${KarandashError.INVALID_YAML_SYNTAX.bracketCode} Invalid YAML syntax: ${e.message}", e)
        }
    }

    private fun parseYamlToJsonElement(yamlContent: String): JsonElement =
        when (val element = Yaml.decodeYamlFromString(yamlContent)) {
            is YamlMap -> yamlMapToJsonObject(element)

            is YamlNull -> JsonObject(emptyMap())

            // A bare scalar (e.g. an HTML login page served instead of the config) is not a config.
            else -> throw IllegalArgumentException(
                "Invalid YAML structure: expected map at root level"
            )
        }

    private fun yamlMapToJsonObject(yamlMap: YamlMap): JsonObject {
        val entries = mutableMapOf<String, JsonElement>()
        for ((key, value) in yamlMap) {
            val keyStr = when (key) {
                is YamlLiteral -> key.content
                else -> key.toString()
            }
            entries[keyStr] = yamlElementToJsonElement(value)
        }
        return JsonObject(entries)
    }

    private fun yamlElementToJsonElement(element: YamlElement): JsonElement =
        when (element) {
            is YamlNull -> {
                JsonNull
            }

            is YamlLiteral -> {
                val content = element.content
                when {
                    content.startsWith(ColonScalarQuoter.QUOTED_MARKER) -> {
                        JsonPrimitive(content.removePrefix(ColonScalarQuoter.QUOTED_MARKER))
                    }

                    content == "null" -> {
                        JsonNull
                    }

                    content == "true" -> {
                        JsonPrimitive(true)
                    }

                    content == "false" -> {
                        JsonPrimitive(false)
                    }

                    content.toLongOrNull() != null && !content.contains(".") -> {
                        val longVal = content.toLong()
                        if (longVal in Int.MIN_VALUE..Int.MAX_VALUE) JsonPrimitive(longVal.toInt()) else JsonPrimitive(longVal)
                    }

                    STRUCTURAL_DOUBLE.matches(content) && content.toDoubleOrNull() != null -> {
                        JsonPrimitive(content.toDouble())
                    }

                    else -> {
                        JsonPrimitive(content)
                    }
                }
            }

            is YamlMap -> {
                yamlMapToJsonObject(element)
            }

            is YamlList -> {
                JsonArray(element.map { yamlElementToJsonElement(it) })
            }
        }

    private fun isEmptyYaml(content: String): Boolean = content.lines().all { it.trim().isEmpty() || it.trim().startsWith("#") }
}
