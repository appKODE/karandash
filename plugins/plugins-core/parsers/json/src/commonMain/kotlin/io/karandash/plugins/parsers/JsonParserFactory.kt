package io.karandash.plugins.parsers

import io.karandash.api.module.ConfigFormatModule
import io.karandash.plugins.parsers.json.JsonFormatModule

/**
 * Factory function that creates a [JsonFormatModule] instance.
 *
 * Exposed from [io.karandash.plugins.parsers] (without the `json` sub-package)
 * to avoid import conflicts with [kotlinx.serialization.json] in consuming modules.
 */
fun createJsonFormatModule(): ConfigFormatModule = JsonFormatModule()
