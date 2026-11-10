package io.karandash.loader

import io.karandash.api.PluginContext
import io.karandash.logger.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Logger that keeps warnings and errors so tests can assert on user-visible messages. */
class RecordingLogger : Logger {
    val warnings = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warn(message: String) {
        warnings += message
    }

    override fun error(message: String) {
        errors += message
    }
}

/** Plugin context that records what guest code sends through `context.warn`. */
class RecordingPluginContext : PluginContext {
    val guestWarnings = mutableListOf<String>()
    override val pluginConfig: JsonElement = JsonObject(emptyMap())
    override val hostVersion: String = "0.1.0-test"
    override val workingDirectory: String = "."

    override fun warn(message: String) {
        guestWarnings += message
    }

    override fun env(name: String): String? = null
}
