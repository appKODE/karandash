package io.karandash.plugins.parsers.json

import io.karandash.sdk.config.ConfigFormatModuleAdapter

/**
 * ServiceLoader entry point for the JSON config format parser.
 */
class JsonFormatModuleLoader : ConfigFormatModuleAdapter(JsonFormatModule())
