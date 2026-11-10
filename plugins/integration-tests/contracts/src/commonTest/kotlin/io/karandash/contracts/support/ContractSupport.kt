package io.karandash.contracts.support

import io.karandash.config.Config
import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.logger.Logger
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.serialization.json.JsonElement

/** Parses a YAML config string through the production YAML parser into a [JsonElement]. */
suspend fun yamlToJson(yaml: String): JsonElement = YamlFormatModule().parseToJsonElement(yaml.trimIndent())

/** Parses a YAML config string through YAML parser + [ConfigMapParser], exactly as the CLI does. */
suspend fun parseYamlConfig(yaml: String): Config = ConfigMapParser.parseFullConfig(yamlToJson(yaml)).config

/** Logger that records every message with its level prefix (`DEBUG:`, `INFO:`, `WARN:`, `ERROR:`). */
class CapturingLogger : Logger {
    val messages = mutableListOf<String>()

    val warnings: List<String> get() = messages.filter { it.startsWith("WARN:") || it.startsWith("ERROR:") }

    override val isDebugEnabled: Boolean get() = true

    override fun debug(message: String) {
        messages += "DEBUG: $message"
    }

    override fun info(message: String) {
        messages += "INFO: $message"
    }

    override fun warn(message: String) {
        messages += "WARN: $message"
    }

    override fun error(message: String) {
        messages += "ERROR: $message"
    }
}
