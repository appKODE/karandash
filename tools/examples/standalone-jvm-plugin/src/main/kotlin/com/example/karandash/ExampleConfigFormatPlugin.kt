package com.example.karandash

import io.karandash.api.module.ConfigFormatModule
import io.karandash.sdk.config.ConfigFormatModuleAdapter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Standalone example Karandash config format plugin.
 *
 * A [ConfigFormatModule] is not a [io.karandash.api.Module], so it is contributed from a JAR by wrapping it
 * in [ConfigFormatModuleAdapter] (a `Module` of type `CONFIG_FORMAT`) and listing the adapter in the
 * ServiceLoader services file. The host then finds it through `ModuleRegistry.configFormats()`.
 *
 * With this plugin installed, `karandash.properties` / `.karandash/config.properties` are discovered as configs.
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleConfigFormatPlugin : ConfigFormatModuleAdapter(ExamplePropertiesFormat())

/**
 * A trivial `key=value` config format, for illustration.
 *
 * Lines starting with `#` are comments. Each `key=value` pair becomes a string entry of the resulting object.
 */
class ExamplePropertiesFormat : ConfigFormatModule {
    override val formatId: String = "example-properties"

    // The host matches the text after the last '.', so `karandash.properties` matches "properties".
    override val fileExtensions: Set<String> = setOf("properties")

    override suspend fun parseToJsonElement(content: String): JsonElement =
        JsonObject(entries(content).associate { (key, value) -> key to JsonPrimitive(value) })

    /** Reads only the `extends` entry; `extends=null` clears inheritance. */
    override suspend fun parseExtendsOnly(content: String): JsonElement? {
        val value = entries(content).lastOrNull { it.first == "extends" }?.second ?: return null
        return if (value == "null") JsonNull else JsonPrimitive(value)
    }

    private fun entries(content: String): List<Pair<String, String>> =
        content
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.indexOf('=') > 0 }
            .map { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim() }
}
