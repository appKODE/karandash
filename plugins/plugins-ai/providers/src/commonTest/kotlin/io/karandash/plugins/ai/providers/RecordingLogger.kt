package io.karandash.plugins.ai.providers

import io.karandash.api.PluginContext
import io.karandash.sdk.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Logger that records each line as `LEVEL message`. */
internal class RecordingLogger : Logger {
    val lines = mutableListOf<String>()

    override val isDebugEnabled: Boolean get() = true

    override fun debug(message: String) {
        lines += "DEBUG $message"
    }

    override fun info(message: String) {
        lines += "INFO $message"
    }

    override fun warn(message: String) {
        lines += "WARN $message"
    }

    override fun error(message: String) {
        lines += "ERROR $message"
    }
}

/** Minimal [PluginContext] carrying [logger] and a fixed env. */
internal class RecordingContext(
    override val logger: Logger,
    private val envVars: Map<String, String> = emptyMap(),
) : PluginContext {
    override val pluginConfig: JsonElement = JsonObject(emptyMap())
    override val hostVersion: String = "test"
    override val workingDirectory: String = "/tmp"

    override fun env(name: String): String? = envVars[name]
}
