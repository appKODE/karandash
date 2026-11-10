package io.karandash.testfixtures

import io.karandash.api.PluginContext
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class TestPluginContext(
    override val pluginConfig: JsonElement = JsonObject(emptyMap()),
    private val envValues: Map<String, String> = emptyMap(),
    override val allowLocalhost: Boolean = false,
    override val workingDirectory: String = "/tmp",
) : PluginContext {
    override val hostVersion: String = "test"
    val logs = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    override val logger: Logger = object : Logger {
        override fun debug(message: String) {
            logs.add(message)
        }

        override fun info(message: String) {
            logs.add(message)
        }

        override fun warn(message: String) {
            warnings.add(message)
        }

        override fun error(message: String) {
            warnings.add(message)
        }
    }

    override fun env(name: String): String? = envValues[name]
}
